#pragma once

#include <atomic>
#include <cstdint>
#include <thread>
#include <vector>

#include <libusb.h>

namespace pulsar {

/**
 * USB 音频等时传输泵——两阶段生命周期。
 *
 * 设计动机（研究文档坑 #1）：切歌/换采样率时绝不能释放流接口，否则内核
 * snd-usb-audio 会短暂抢回接口，下一次 claim 返回 EBUSY，播放哑死直到拔线。
 * 因此把生命周期拆成两层：
 *
 * - 设备级 [open]/[close]：wrap fd → claim → set alt。开关开启/关闭时各一次。
 * - 流级 [start_stream]/[stop_stream]：SET_CUR 采样率 + 等时泵启停。可反复
 *   调用，始终保留 claim；换 alt（位深变化）只 set_interface_alt_setting。
 *
 * 数据流：ExoPlayer 音频线程 [write] → SPSC ring → 事件线程按 16.16 定点
 * 帧率累加器为每个等时包算好帧数，从 ring 拉数据（不足补零）→ 设备。
 *
 * 时序与字节布局参数（ticks_per_packet / bytes_per_frame / q16）由 Kotlin
 * 的 UsbStreamParams 推导后传入——native 不做格式决策，只按参数执行。
 *
 * 线程：生产者=音频线程（write/flush），消费者=事件线程（refill/resubmit）。
 * [stop_stream]/[close] 会 join 事件线程，因此只能从非事件线程调用。
 */
class UsbAudioDriver {
 public:
  /** 打开/启动失败的错误阶段。JNI 层原样转成负错误码返回给 Kotlin。 */
  enum class Error : int32_t {
    kOk = 0,
    kInit = -1,     // libusb_init 失败
    kWrap = -2,     // libusb_wrap_sys_device 失败
    kClaim = -3,    // claim_interface 失败（多为内核 snd-usb-audio 占用）
    kSetAlt = -4,   // set_interface_alt_setting 失败
    kSetRate = -5,  // SET_CUR 采样率失败
    kAlloc = -6,    // 传输/缓冲分配失败
    kSubmit = -7,   // 初始传输提交失败
  };

  // ---- 设备级生命周期 ----------------------------------------------------

  /**
   * @param fd 来自 UsbDeviceConnection.getFileDescriptor()。fd 所有权归 Kotlin
   *   （UsbDeviceConnection）：libusb 1.0.27 的 fd_keep=1 使 libusb_close 不关 fd，
   *   native 从不 close，由调用方在 close() 之后关连接。
   * @param initial_alt_setting open 时设定的初始 alt setting（非零带宽）。
   */
  UsbAudioDriver(int fd, int interface_number, int initial_alt_setting,
                 int endpoint_address, int uac_version, int clock_id,
                 int control_interface_number, int feature_unit_id,
                 int volume_channel_mask);
  ~UsbAudioDriver();

  UsbAudioDriver(const UsbAudioDriver&) = delete;
  UsbAudioDriver& operator=(const UsbAudioDriver&) = delete;

  /** 包装 fd、claim 流接口、设初始 alt。失败时对象不可复用，由调用方销毁。 */
  Error open();

  /** 停止泵（若在流）→ 释放接口 → 关闭句柄 → 退出 context。幂等。 */
  void close();

  // ---- 流级生命周期（可多次 start/stop，始终保留 claim）-------------------

  /**
   * 设为新采样率并启动等时泵。若已在流：先停泵（不释放接口），换 alt（若变）、
   * 换采样率，再重启。失败时保持接口 claim（可重试）。
   *
   * @param max_packet_size 当前 alt 的端点最大包字节数（换 alt 可能变化）。
   * @param alt_setting 当前格式对应的 alt setting（位深变化时与初始不同）。
   */
  Error start_stream(int sample_rate, int ticks_per_packet, int bytes_per_frame,
                     uint64_t frames_per_uframe_q16, int max_packet_size, int alt_setting);

  /** 停止泵，保留接口 claim 与设备句柄。幂等。 */
  void stop_stream();

  /** 推入 PCM 数据，返回实际接收的字节数（ring 满时小于 len）。 */
  int write(const uint8_t* data, int len);

  /** 丢弃尚未发出的 PCM（切歌/暂停），在途传输照常跑（不足自动补零）。 */
  void flush();

  /** 设备已实际消费的帧数（供 ExoPlayer 进度与看门狗）。 */
  uint64_t played_frames() const {
    return played_frames_.load(std::memory_order_relaxed);
  }

  /** 累计欠载次数（refill 时补零的传输），排查用。 */
  uint64_t underruns() const {
    return underruns_.load(std::memory_order_relaxed);
  }

  /** 泵当前是否在运行（流级）。 */
  bool streaming() const {
    return pumping_.load(std::memory_order_relaxed);
  }

  /**
   * 设备真实链路速度对应的等时时序基准是否为高速（每秒 8000 微帧）。
   * 由 open() 用 libusb_get_device_speed 探测。HIGH 及以上（SUPER/SUPER_PLUS）
   * 都按 8000 微帧/秒的高速时序；LOW/FULL/UNKNOWN 按全速 1000 帧/秒。
   * Kotlin 据此推导正确的每包帧数，避免把 8 倍数据塞进高速微帧导致设备掉线。
   */
  bool is_high_speed() const { return is_high_speed_; }

  /**
   * 设置输出增益百分比（0..100）。100 = 原样直送（bit-perfect，无衰减）；
   * 更低值把 16bit 样本整体线性衰减，给波峰留余量避免满刻度削顶。
   * 可从任意线程调用（音频线程经 JNI）——用原子存储，事件线程 refill 时读取。
   */
  void set_volume(int percent);

  /**
   * 暂停用的"静音门"：置位后 refill() 直接送静音、不消费 ring、不计已播放帧，
   * 但绝不停泵——不释放接口、不重发 UAC2 SET_CUR 采样率控制传输、不重触发
   * 300ms 预缓冲。这些操作原本只为切歌/换格式设计；套用到每次暂停/恢复上，
   * 部分廉价 UAC2 小尾巴收到 SET_CUR 后会短暂重锁 PLL/静音，表现为暂停恢复
   * 断断续续（真机验证确认）。可从任意线程调用（音频线程经 JNI），原子存储，
   * 事件线程 refill 时读取。
   */
  void set_muted(bool muted);

  /** UAC2 Feature Unit 主音量 GET_RANGE 是否返回了合法范围。 */
  bool has_hardware_volume() const { return hardware_volume_supported_; }
  int hardware_volume_min_db256() const { return hardware_volume_min_db256_; }
  int hardware_volume_max_db256() const { return hardware_volume_max_db256_; }
  int hardware_volume_step_db256() const { return hardware_volume_step_db256_; }

  /**
   * 事件线程 join 失败（设备彻底无响应）后为 true。此时对象绝不能被 delete
   * ——事件线程仍可能运行、仍可能访问 contexts_/transfer_buffers_/libusb 资源，
   * 析构一个 joinable 的 std::thread 会 std::terminate() 崩溃整个进程。
   * JNI 层据此跳过 delete，接受一次性内存泄漏而不是 UB。
   */
  bool is_thread_leaked() const { return thread_leaked_; }

 private:
  // 在途传输深度按目标时长动态计算（start_stream）：高速时序每个传输携带
  // 1ms 音频（8 微帧 × 125µs），全速为 8ms（8 帧 × 1ms）。固定 16 个在高速
  // 设备上只有 16ms 在途——事件线程被前台负载饿出 >16ms 的调度空隙，内核
  // 等时队列即耗尽，设备端补零（爆音）；饿超看门狗阈值还会触发回退重启
  // （听感=往后跳秒）。按时长取深度（~120ms）只加队列长度，不改每包时序，
  // 包级节奏仍由内核等时调度保证。上限防极端内存/延迟（seek 后在途旧音频
  // 尾巴的时长 = 在途时长），下限保底抗抖。
  static constexpr int kNumPackets = 8;  // 每个传输的等时包数
  static constexpr int kInFlightTargetMs = 120;  // 在途缓冲目标时长
  static constexpr int kMinTransfers = 16;
  static constexpr int kMaxTransfers = 128;
  static constexpr uint64_t kQ16Unit = 65536ULL;
  static constexpr int kMaxConsecutiveFailures = 3;  // 连续失败上限，触发即停泵
  static constexpr int kDiagMaxCallbacks = 16;  // 一次性诊断：仅打印前若干次回调
  static constexpr uint64_t kTelemetryInterval = 500;  // 节流遥测：每 N 次回调打一条
  static constexpr int kPrebufferMs = 300;  // 流启动/seek 预缓冲时长（ms）
  static constexpr int kVolumeUnity = 100;  // 增益满刻度：100 = 原样直送（bit-perfect）

  // 设备级参数（open 后不变）
  const int fd_;
  const int interface_number_;
  int alt_setting_;              // 当前生效的 alt（start_stream 换格式时可能改）
  const int endpoint_address_;
  const int uac_version_;
  const int clock_id_;
  /** UAC2 SET_CUR / GET_RANGE 的 wIndex 低字节（AudioControl 接口号）。 */
  const int control_interface_number_;
  /** 播放链路上声明可写 Volume 的 UAC2 Feature Unit；0 表示没有。 */
  const int feature_unit_id_;
  /** 可写 Volume 声道位掩码：bit N 对应 UAC2 channel N。 */
  const int volume_channel_mask_;
  // 设备真实链路速度是否为高速时序（open 探测；见 is_high_speed()）。
  bool is_high_speed_ = false;

  // 流级参数（start_stream 每次设置）
  int sample_rate_ = 0;
  int ticks_per_packet_ = 0;
  int bytes_per_frame_ = 0;
  uint64_t frames_per_uframe_q16_ = 0;
  int max_packet_size_ = 0;
  // 本流在途传输数（build_pump 按速度时序算出：目标 ~kInFlightTargetMs 的
  // 音频深度，钳位 [kMinTransfers, kMaxTransfers]）。池/缓冲均按它分配。
  int num_transfers_ = kMinTransfers;

  // 预缓冲：流启动/重启后先让 ring 累积到 prebuffer_target_bytes_ 再开始向 DAC
  // 送真实数据，避免 ring 从 0 开始、没填满就播导致的切歌/恢复失真。
  // pump_ready_ 仅事件线程读写（start_stream 建线程前置 false，refill 里置 true），
  // 无数据竞争。prebuffer_target_bytes_ 由 start_stream 按采样率×时长算出。
  bool pump_ready_ = false;
  uint32_t prebuffer_target_bytes_ = 0;

  libusb_context* ctx_ = nullptr;
  libusb_device_handle* dev_ = nullptr;
  // UAC2 采样率 SET_CUR 收件人是 AC 接口，须额外 claim 它（否则内核占用导致
  // LIBUSB_ERROR_IO）。仅当控制接口号与流接口号不同才 claim；cleanup 据此释放。
  bool control_interface_claimed_ = false;
  bool hardware_volume_supported_ = false;
  int hardware_volume_min_db256_ = 0;
  int hardware_volume_max_db256_ = 0;
  int hardware_volume_step_db256_ = 0;

  std::thread event_thread_;
  std::atomic<bool> pumping_{false};   // 泵正在运行
  std::atomic<bool> closed_{false};    // close() 已调用（幂等/析构保护）
  std::atomic<int> in_flight_{0};
  // 排干超时（2 秒）后仍会继续尝试 join 事件线程——event_loop() 的退出条件
  // 只看 pumping_/in_flight_，一旦剩余传输被设备/内核实际完成或取消回调
  // 触发，线程会自行退出，join 是安全的。只有 join 本身也失败（线程彻底
  // 卡死）时才置位：此时该对象绝不能被 delete——析构 joinable 的 std::thread
  // 会 std::terminate() 崩溃整个进程，回调仍可能引用 contexts_/
  // transfer_buffers_。JNI 层据此跳过 delete，接受一次性内存泄漏而不是 UB。
  // 仅由非事件线程读写（stop_pump_and_drain 单写者）。
  bool thread_leaked_ = false;

  // 16.16 定点帧率累加器（Bresenham 风格，公式与 Kotlin IsoFrameAccumulator 一致）。
  // 只被事件线程访问，无需原子。
  uint64_t uframe_acc_ = 0;

  // 输出增益百分比（0..100，默认满刻度=bit-perfect）。set_volume 从音频线程经
  // JNI 写，事件线程 refill 读——用原子避免撕裂。100 时 refill 走无衰减快路径。
  std::atomic<int> volume_percent_{kVolumeUnity};

  // 暂停用静音门（见 set_muted 注释）。音频线程经 JNI 写，事件线程 refill 读。
  std::atomic<bool> muted_{false};

  // SPSC ring buffer（大小取 2 的幂）
  std::vector<uint8_t> ring_;
  uint32_t ring_size_ = 0;
  uint32_t ring_mask_ = 0;
  std::atomic<uint32_t> ring_write_{0};  // 生产者（音频线程）写位置
  std::atomic<uint32_t> ring_read_{0};   // 消费者（事件线程）读位置——单写者

  // flush 语义：音频线程把"此刻的写位置"记下来，事件线程在下次 refill 时
  // 据此重置读指针。ring_read_ 永远只有事件线程写，SPSC 不变量不受破坏。
  std::atomic<uint32_t> pending_flush_{0};

  struct TransferContext {
    UsbAudioDriver* driver;
    int index;
  };

  std::vector<uint8_t> transfer_buffers_;
  std::vector<TransferContext> contexts_;
  std::vector<libusb_transfer*> transfers_;
  std::vector<uint32_t> transfer_frames_;
  std::vector<int> failure_counts_;  // 每传输连续失败计数（事件线程单写）

  std::atomic<uint64_t> played_frames_{0};
  std::atomic<uint64_t> underruns_{0};

  // 一次性诊断：记录前若干次传输完成回调的状态与每包实际长度，排查"无推进"用。
  // 仅事件线程写，start_stream 重置。达到上限后不再打印，避免刷屏。
  int diag_callback_count_ = 0;

  // 节流遥测：每 kTelemetryInterval 次回调打一次欠载/已播放/ring 填充量，
  // 排查稳态卡顿（欠载 vs 进度漂移）用。仅事件线程写。
  uint64_t telemetry_counter_ = 0;

  void event_loop();
  static void on_transfer_complete(libusb_transfer* transfer);
  uint32_t refill(libusb_transfer* transfer, uint8_t* buffer, uint32_t capacity);
  int submit(int index);
  int set_sample_rate(int sample_rate);
  /** 查询 UAC2 Feature Unit 各目标声道的 RANGE；范围不一致则禁用硬件音量。 */
  void probe_hardware_volume();
  static int read_signed_le16(const uint8_t* data);
  /** 取消在途传输并等待排空、join 事件线程；保留 claim 与句柄。 */
  void stop_pump_and_drain();
  /** 分配 ring 与传输池（start_stream 内调用）；失败返回 kAlloc 并清理已分配部分。 */
  Error build_pump();
  /** 释放传输池与 ring（pump 已停）。 */
  void release_pump();
  /** 释放 libusb 资源（interface/handle/ctx），关闭标记。 */
  void cleanup();

  // ring 访问（生产者 write / 消费者事件线程）
  uint32_t ring_read(uint8_t* out, uint32_t len);
};

}  // namespace pulsar
