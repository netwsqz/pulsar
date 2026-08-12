#include "usb_audio_driver.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include <android/log.h>

#define LOGE(...) \
  __android_log_print(ANDROID_LOG_ERROR, "PulsarUsb", __VA_ARGS__)
#define LOGW(...) \
  __android_log_print(ANDROID_LOG_WARN, "PulsarUsb", __VA_ARGS__)

namespace pulsar {

namespace {

uint32_t next_power_of_two(uint32_t v) {
  if (v == 0) return 1;
  v--;
  v |= v >> 1;
  v |= v >> 2;
  v |= v >> 4;
  v |= v >> 8;
  v |= v >> 16;
  return v + 1;
}

}  // namespace

UsbAudioDriver::UsbAudioDriver(int fd, int interface_number, int alt_setting,
                               int endpoint_address, int uac_version, int clock_id,
                               int control_interface_number, int feature_unit_id,
                               int volume_channel_mask)
    : fd_(fd),
      interface_number_(interface_number),
      alt_setting_(alt_setting),
      endpoint_address_(endpoint_address),
      uac_version_(uac_version),
      clock_id_(clock_id),
      control_interface_number_(control_interface_number),
      feature_unit_id_(feature_unit_id),
      volume_channel_mask_(volume_channel_mask) {}

UsbAudioDriver::~UsbAudioDriver() {
  close();
}

UsbAudioDriver::Error UsbAudioDriver::open() {
  // wrapped system fd 不需要 libusb 枚举设备；初始化时就关闭发现，避免 Android
  // 应用进程因无权访问 usbfs 枚举而在 libusb_init 阶段失败。
  const struct libusb_init_option options[] = {
      {LIBUSB_OPTION_NO_DEVICE_DISCOVERY, {.ival = 0}},
  };
  int rc = libusb_init_context(&ctx_, options, 1);
  if (rc != 0) {
    ctx_ = nullptr;
    LOGE("libusb_init_context(no discovery) failed: %s", libusb_error_name(rc));
    return Error::kInit;
  }

  rc = libusb_wrap_sys_device(ctx_, fd_, &dev_);
  if (rc != 0) {
    LOGE("libusb_wrap_sys_device failed: %s", libusb_error_name(rc));
    libusb_exit(ctx_);
    ctx_ = nullptr;
    dev_ = nullptr;
    // fd 所有权归 Kotlin：libusb 1.0.27 的 fd_keep=1 使 libusb 从不关闭
    // wrapped fd，native 也从不 close。Kotlin 在 nativeOpen 返回负错误码后
    // 自行 close UsbDeviceConnection。
    return Error::kWrap;
  }

  // 查询设备真实链路速度，决定等时时序基准（高速 8000 微帧/秒 vs 全速 1000 帧/秒）。
  // libusb_get_device 从句柄取设备不会失败；libusb_get_device_speed 返回枚举。
  // HIGH 及以上按高速时序；LOW/FULL/UNKNOWN 保守按全速。Kotlin 据此推导每包帧数，
  // 避免把 8 倍数据塞进高速微帧导致设备掉线（研究文档坑：isHighSpeed 不能硬编码）。
  libusb_device* device = libusb_get_device(dev_);
  int speed = libusb_get_device_speed(device);
  const char* speed_name = "UNKNOWN";
  switch (speed) {
    case LIBUSB_SPEED_LOW: speed_name = "LOW(1.5Mbps)"; break;
    case LIBUSB_SPEED_FULL: speed_name = "FULL(12Mbps)"; break;
    case LIBUSB_SPEED_HIGH: speed_name = "HIGH(480Mbps)"; break;
    case LIBUSB_SPEED_SUPER: speed_name = "SUPER(5Gbps)"; break;
    case LIBUSB_SPEED_SUPER_PLUS: speed_name = "SUPER_PLUS(10Gbps)"; break;
  }
  is_high_speed_ = speed == LIBUSB_SPEED_HIGH || speed == LIBUSB_SPEED_SUPER ||
                   speed == LIBUSB_SPEED_SUPER_PLUS;
  LOGE("Device link speed: %s (%d), high_speed_timing=%d", speed_name, speed,
       is_high_speed_ ? 1 : 0);


  // 独占模式需要在 claim 时让 libusb 原子地分离占用该接口的内核驱动。
  // 成功 release 时 libusb 会尝试重新 attach；失败仍会保留原始 claim 错误。
  rc = libusb_set_auto_detach_kernel_driver(dev_, 1);
  if (rc != LIBUSB_SUCCESS) {
    LOGE("set_auto_detach_kernel_driver failed: %s", libusb_error_name(rc));
    libusb_close(dev_);
    dev_ = nullptr;
    libusb_exit(ctx_);
    ctx_ = nullptr;
    return Error::kClaim;
  }

  rc = libusb_claim_interface(dev_, interface_number_);
  if (rc != 0) {
    LOGE("claim_interface(%d) failed with auto-detach: %s", interface_number_,
         libusb_error_name(rc));
    libusb_close(dev_);
    dev_ = nullptr;
    libusb_exit(ctx_);
    ctx_ = nullptr;
    return Error::kClaim;
  }

  rc = libusb_set_interface_alt_setting(dev_, interface_number_, alt_setting_);
  if (rc != 0) {
    LOGE("set_interface_alt_setting(%d, %d) failed: %s", interface_number_, alt_setting_,
         libusb_error_name(rc));
    libusb_release_interface(dev_, interface_number_);
    libusb_close(dev_);
    dev_ = nullptr;
    libusb_exit(ctx_);
    ctx_ = nullptr;
    return Error::kSetAlt;
  }

  // UAC2：采样率 SET_CUR 的收件人是 AudioControl 接口。Android usbfs 要求对
  // interface-recipient 的控制传输必须先 claim 该接口，否则内核 snd-usb-audio
  // 仍占用它，SET_CUR 返回 LIBUSB_ERROR_IO。仅当控制接口与流接口不同才需额外
  // claim（相同则已被上面 claim 覆盖）。auto-detach 已开启，会顺带分离内核驱动。
  if (uac_version_ == 0x0200 && control_interface_number_ != interface_number_) {
    rc = libusb_claim_interface(dev_, control_interface_number_);
    if (rc != 0) {
      LOGE("claim control interface(%d) failed: %s", control_interface_number_,
           libusb_error_name(rc));
      libusb_release_interface(dev_, interface_number_);
      libusb_close(dev_);
      dev_ = nullptr;
      libusb_exit(ctx_);
      ctx_ = nullptr;
      return Error::kClaim;
    }
    control_interface_claimed_ = true;
  }

  // Feature Unit 探测必须在 AC 接口 claim 后执行。失败只表示硬件音量不可用，
  // 不影响 USB 音频流打开，后续仍使用软件数字衰减。
  probe_hardware_volume();

  // 采样率不在 open() 里设——它是流级参数，由 start_stream 每次设置。
  // 换格式/切歌只换采样率，不必重新 claim 接口（研究文档坑 #1）。
  return Error::kOk;
}

UsbAudioDriver::Error UsbAudioDriver::start_stream(
    int sample_rate, int ticks_per_packet, int bytes_per_frame,
    uint64_t frames_per_uframe_q16, int max_packet_size, int alt_setting) {
  if (closed_.load(std::memory_order_acquire) || thread_leaked_) {
    LOGE("start_stream on closed/leaked driver");
    return Error::kSubmit;
  }

  // 若已在流：先停泵（保留接口 claim），换参数后重启。
  if (pumping_.load(std::memory_order_acquire)) {
    stop_pump_and_drain();
    if (thread_leaked_) return Error::kSubmit;
  }

  sample_rate_ = sample_rate;
  ticks_per_packet_ = ticks_per_packet;
  bytes_per_frame_ = bytes_per_frame;
  frames_per_uframe_q16_ = frames_per_uframe_q16;
  max_packet_size_ = max_packet_size;

  // 换 alt（位深变化时与初始不同）。
  if (alt_setting != alt_setting_) {
    int rc = libusb_set_interface_alt_setting(dev_, interface_number_, alt_setting);
    if (rc != 0) {
      LOGE("set_interface_alt_setting(%d, %d) failed: %s", interface_number_, alt_setting,
           libusb_error_name(rc));
      return Error::kSetAlt;
    }
    alt_setting_ = alt_setting;
  }

  // 换采样率。
  int rc = set_sample_rate(sample_rate);
  if (rc != 0) {
    LOGE("SET_CUR sample rate %d failed: %s", sample_rate, libusb_error_name(rc));
    return Error::kSetRate;
  }

  // 重置流状态：累加器、ring、flush 标记（上一会话的残留数据不再需要）。
  uframe_acc_ = 0;
  ring_write_.store(0, std::memory_order_release);
  ring_read_.store(0, std::memory_order_release);
  pending_flush_.store(0, std::memory_order_release);
  diag_callback_count_ = 0;  // 重置一次性回调诊断

  // 预缓冲：事件线程先发静音、不消费 ring，待 ring 累积到目标水位再切正常播放。
  // 目标 = 采样率 × 预缓冲时长 × 每帧字节；置于建事件线程之前，无竞争。
  pump_ready_ = false;
  prebuffer_target_bytes_ = static_cast<uint32_t>(
      static_cast<uint64_t>(sample_rate) * kPrebufferMs / 1000 * bytes_per_frame);

  Error err = build_pump();
  if (err != Error::kOk) return err;

  // 提交初始传输（事件线程最后再启动，提交阶段没有回调竞争）。
  pumping_.store(true, std::memory_order_release);
  in_flight_.store(0, std::memory_order_release);
  for (int i = 0; i < kNumTransfers; i++) {
    if (submit(i) != 0) {
      LOGE("submit initial transfer %d failed", i);
      pumping_.store(false, std::memory_order_release);
      stop_pump_and_drain();
      return Error::kSubmit;
    }
    in_flight_.fetch_add(1, std::memory_order_release);
  }

  event_thread_ = std::thread(&UsbAudioDriver::event_loop, this);
  return Error::kOk;
}

void UsbAudioDriver::set_volume(int percent) {
  // 钳位到合法区间，100 = 原样直送。音频线程（经 JNI）调用，原子写。
  if (percent < 0) percent = 0;
  if (percent > 100) percent = 100;
  volume_percent_.store(percent, std::memory_order_relaxed);
}

void UsbAudioDriver::set_muted(bool muted) {
  muted_.store(muted, std::memory_order_relaxed);
}

void UsbAudioDriver::stop_stream() {
  if (thread_leaked_) return;  // 事件线程已确认卡死：绝不再触碰任何相关状态
  stop_pump_and_drain();
}

void UsbAudioDriver::close() {
  if (closed_.exchange(true)) return;
  stop_stream();

  if (thread_leaked_) {
    // join 本身失败：事件线程仍在运行，可能仍在访问 contexts_/transfer_buffers_/
    // libusb 资源。不能释放任何东西，也不能让调用方 delete 这个对象——
    // 只能原地泄漏，换取不发生 UB/崩溃。
    LOGE("close on driver with leaked event thread: skipping all cleanup");
    return;
  }

  release_pump();  // 兜底：未 start_stream 或已释放时为空操作
  cleanup();
  // fd 所有权归 Kotlin（UsbDeviceConnection）：vendored libusb 1.0.27 的
  // fd_keep=1 使 libusb_close 不关 wrapped fd，native 也从不 close。
  // 调用方在 nativeClose 之后 close 连接。
}

void UsbAudioDriver::stop_pump_and_drain() {
  pumping_.store(false, std::memory_order_release);

  // 取消所有在途传输，让事件线程尽快收尾。libusb_cancel_transfer 对已完成/未
  // 提交的传输返回 NOT_FOUND，可忽略。
  for (auto* t : transfers_) {
    if (t != nullptr) libusb_cancel_transfer(t);
  }

  // 暂停/停泵必须立即生效（消除"暂停后还播 1 秒"残留）。取消后**立即排干**：
  // 同步等所有在途传输完成（CANCELLED 回调仍会触发、递减 in_flight_），再 join
  // 事件线程。这样 stopStream 返回时泵真正停了。排干最多 2 秒（典型 < 50ms）。
  if (in_flight_.load(std::memory_order_acquire) > 0) {
    struct timeval tv = {0, 50000};  // 50ms 超时
    int guard = 0;
    do {
      int rc = libusb_handle_events_timeout(ctx_, &tv);
      if (rc != 0 && rc != LIBUSB_ERROR_INTERRUPTED) {
        LOGW("drain: handle_events error %s at guard %d", libusb_error_name(rc), guard);
      }
      guard++;
    } while (in_flight_.load(std::memory_order_acquire) > 0 && guard < 40);

    if (in_flight_.load(std::memory_order_acquire) > 0) {
      // 排干超时：仍有在途传输未完成。不代表事件线程已死——只要这些传输
      // 最终被设备/内核完成或取消回调触发，event_loop() 会自行退出
      // （退出条件只看 pumping_/in_flight_）。继续尝试 join，比跳过 join
      // 后被析构一个 joinable 的线程（std::terminate 崩溃整个进程）安全。
      LOGE("drain timed out after 2s, %d still in-flight — will still try to join",
           in_flight_.load(std::memory_order_acquire));
    }
  }

  if (event_thread_.joinable()) {
    event_thread_.join();
  }

  if (in_flight_.load(std::memory_order_acquire) > 0) {
    // join 已返回但仍有传输未完成——理论上不该发生（join 返回即线程已退出，
    // event_loop 退出前 in_flight_ 应已归零）。防御性升级为彻底放弃，不再
    // 释放任何 libusb 资源，也不允许调用方 delete 该对象。
    LOGE("event thread joined but in_flight_=%d remains — treating as leaked",
         in_flight_.load(std::memory_order_acquire));
    thread_leaked_ = true;
    return;
  }

  release_pump();
}

void UsbAudioDriver::release_pump() {
  for (auto* t : transfers_) {
    if (t != nullptr) libusb_free_transfer(t);
  }
  transfers_.clear();
  contexts_.clear();
  transfer_buffers_.clear();
  transfer_frames_.clear();
  failure_counts_.clear();
  ring_.clear();
  ring_size_ = 0;
  ring_mask_ = 0;
}

void UsbAudioDriver::cleanup() {
  if (dev_ != nullptr) {
    // 先释放额外 claim 的控制接口（若有），再释放流接口。顺序无强约束，
    // 但与 open() 的 claim 顺序对称，便于阅读。
    if (control_interface_claimed_) {
      libusb_release_interface(dev_, control_interface_number_);
      control_interface_claimed_ = false;
    }
    libusb_release_interface(dev_, interface_number_);
    libusb_close(dev_);
    dev_ = nullptr;
  }
  if (ctx_ != nullptr) {
    libusb_exit(ctx_);
    ctx_ = nullptr;
  }
}

UsbAudioDriver::Error UsbAudioDriver::build_pump() {
  // ring buffer：bypass 到 USB 泵后，ExoPlayer 的 AudioTrack 缓冲被绕过，有效
  // 音频缓冲就只剩这个 ring。按传输几何算出的下限（约 2 倍在途数据）只有 ~186ms，
  // 扛不住 Jellyfin 网络流的解码/网络抖动，ring 反复见底补零 → 失真。改为按真实
  // 字节率取约 1 秒的目标容量（对齐系统 sink 的抗抖动能力），并保证不小于在途下限。
  const uint64_t in_flight_min = 2ull * kNumTransfers * kNumPackets * max_packet_size_;
  const uint64_t one_second_bytes =
      static_cast<uint64_t>(sample_rate_) * static_cast<uint64_t>(bytes_per_frame_);
  const uint64_t ring_size_u64 = std::max(in_flight_min, one_second_bytes);
  ring_size_ = next_power_of_two(
      static_cast<uint32_t>(std::min(ring_size_u64, uint64_t(1u << 31))));
  ring_mask_ = ring_size_ - 1;
  ring_.resize(ring_size_);

  // 传输池：每个 transfer 一块固定缓冲（kNumPackets * maxPacketSize）
  const uint32_t transfer_capacity = kNumPackets * max_packet_size_;
  transfer_buffers_.resize(kNumTransfers * transfer_capacity);
  contexts_.resize(kNumTransfers);
  transfers_.assign(kNumTransfers, nullptr);
  transfer_frames_.assign(kNumTransfers, 0);
  failure_counts_.assign(kNumTransfers, 0);
  for (int i = 0; i < kNumTransfers; i++) {
    libusb_transfer* t = libusb_alloc_transfer(kNumPackets);
    if (t == nullptr) {
      LOGE("libusb_alloc_transfer(%d) failed", kNumPackets);
      release_pump();  // 释放已分配的传输（未提交，安全）
      return Error::kAlloc;
    }
    transfers_[i] = t;
    contexts_[i] = {this, i};
    uint8_t* buf = transfer_buffers_.data() + i * transfer_capacity;
    libusb_fill_iso_transfer(t, dev_, static_cast<unsigned char>(endpoint_address_), buf, 0,
                             kNumPackets, &UsbAudioDriver::on_transfer_complete, this, 0);
    t->user_data = &contexts_[i];
  }
  return Error::kOk;
}

void UsbAudioDriver::event_loop() {
  while (true) {
    struct timeval tv = {0, 100000};  // 100ms 超时，便于及时退出
    int rc = libusb_handle_events_timeout(ctx_, &tv);
    if (rc == LIBUSB_ERROR_INTERRUPTED) {
      continue;
    }
    if (rc != 0) {
      // 事件循环严重错误：停泵，交给 Kotlin 看门狗兜底
      LOGE("libusb_handle_events failed: %s", libusb_error_name(rc));
      pumping_.store(false, std::memory_order_release);
      break;
    }
    if (!pumping_.load(std::memory_order_acquire) &&
        in_flight_.load(std::memory_order_acquire) == 0) {
      break;
    }
  }
}

void UsbAudioDriver::on_transfer_complete(libusb_transfer* transfer) {
  auto* tc = static_cast<TransferContext*>(transfer->user_data);
  UsbAudioDriver* self = tc->driver;
  const int index = tc->index;

  // 一次性诊断：打印前若干次回调的整体 status + 每包 status/actual_length，
  // 用于区分"回调没触发/包 0 长度/设备拒收"。达到上限后停止，避免刷屏。
  if (self->diag_callback_count_ < kDiagMaxCallbacks) {
    self->diag_callback_count_++;
    int p0_status = transfer->num_iso_packets > 0 ? transfer->iso_packet_desc[0].status : -1;
    unsigned int p0_actual =
        transfer->num_iso_packets > 0 ? transfer->iso_packet_desc[0].actual_length : 0u;
    unsigned int p0_len =
        transfer->num_iso_packets > 0 ? transfer->iso_packet_desc[0].length : 0u;
    LOGE("iso diag #%d transfer %d: status=%d num_packets=%d total_len=%d "
         "p0_status=%d p0_len=%u p0_actual=%u frames=%u",
         self->diag_callback_count_, index, transfer->status, transfer->num_iso_packets,
         transfer->length, p0_status, p0_len, p0_actual, self->transfer_frames_[index]);
  }

  // 节流遥测：每 kTelemetryInterval 次回调打一条运行时账目——欠载累计、已播放
  // 帧、ring 当前填充字节。用于区分"稳态欠载（ring 常空）"与"进度账目漂移"。
  if (++self->telemetry_counter_ % kTelemetryInterval == 0) {
    const uint32_t w = self->ring_write_.load(std::memory_order_acquire);
    const uint32_t r = self->ring_read_.load(std::memory_order_acquire);
    LOGW("iso telemetry: underruns=%llu played=%llu ring_fill=%u/%u",
         static_cast<unsigned long long>(self->underruns_.load(std::memory_order_relaxed)),
         static_cast<unsigned long long>(self->played_frames_.load(std::memory_order_relaxed)),
         w - r, self->ring_size_);
  }

  if (!self->pumping_.load(std::memory_order_acquire)) {
    self->in_flight_.fetch_sub(1, std::memory_order_release);
    return;
  }

  if (transfer->status != LIBUSB_TRANSFER_COMPLETED) {
    if (transfer->status == LIBUSB_TRANSFER_NO_DEVICE ||
        transfer->status == LIBUSB_TRANSFER_ERROR) {
      LOGE("iso transfer %d fatal status: %s", index,
           libusb_error_name(transfer->status));
      self->pumping_.store(false, std::memory_order_release);
      self->in_flight_.fetch_sub(1, std::memory_order_release);
      return;
    }
    // STALL / TIMED_OUT / OVERFLOW：连续失败计数
    self->failure_counts_[index]++;
    if (self->failure_counts_[index] >= kMaxConsecutiveFailures) {
      LOGE("iso transfer %d consecutive failures %d, stopping pump", index,
           self->failure_counts_[index]);
      self->pumping_.store(false, std::memory_order_release);
      self->in_flight_.fetch_sub(1, std::memory_order_release);
      return;
    }
  } else {
    self->failure_counts_[index] = 0;
    // 处理 flush 请求：exchange 原子取走并清零，避免吞掉并发的新 flush；
    // ring_read_ 只被事件线程写，SPSC 不变量不受破坏。
    const uint32_t flush_pos = self->pending_flush_.exchange(0, std::memory_order_acq_rel);
    if (flush_pos != 0) {
      self->ring_read_.store(flush_pos, std::memory_order_release);
    }
    self->played_frames_.fetch_add(self->transfer_frames_[index],
                                   std::memory_order_relaxed);
  }

  int rc = self->submit(index);
  if (rc != 0) {
    LOGE("resubmit transfer %d failed: %s", index, libusb_error_name(rc));
    self->pumping_.store(false, std::memory_order_release);
    self->in_flight_.fetch_sub(1, std::memory_order_release);
  }
}

int UsbAudioDriver::submit(int index) {
  libusb_transfer* t = transfers_[index];
  const uint32_t transfer_capacity = kNumPackets * max_packet_size_;
  uint8_t* buf = transfer_buffers_.data() + index * transfer_capacity;
  transfer_frames_[index] = refill(t, buf, transfer_capacity);

  t->length = 0;
  for (int p = 0; p < kNumPackets; p++) {
    t->length += t->iso_packet_desc[p].length;
  }
  return libusb_submit_transfer(t);
}

uint32_t UsbAudioDriver::refill(libusb_transfer* transfer, uint8_t* buffer,
                                uint32_t capacity) {
  if (bytes_per_frame_ <= 0) return 0;  // 防御：参数异常直接空包（正常不会触发）

  // 输出增益：100 = 原样直送（bit-perfect）；更低值线性衰减，给波峰留余量避免
  // 满刻度削顶（USB 独占绕过系统音量，满刻度直送在大动态段会推过载）。原子读
  // 一次，整个 refill 用同一 gain，避免包间跳变。
  const int volume_percent = volume_percent_.load(std::memory_order_relaxed);

  // 预缓冲门：流启动/切歌后 ring 从 0 开始，若立即消费会在 ExoPlayer 填满前
  // 反复欠载补零（听感=失真/丢音）。未 ready 时先检查 ring 是否已到目标水位；
  // 没到就整批发静音——推进累加器保持包长与总线节奏正确，但不消费 ring、
  // 不计欠载、不推进 played（这段静音是预卷，不算已播放内容，避免位置漂移）。
  if (!pump_ready_) {
    const uint32_t w = ring_write_.load(std::memory_order_acquire);
    const uint32_t r = ring_read_.load(std::memory_order_acquire);
    if (w - r >= prebuffer_target_bytes_) {
      pump_ready_ = true;
      LOGW("prebuffer done: ring_fill=%u target=%u", w - r, prebuffer_target_bytes_);
    }
  }
  const bool prebuffering = !pump_ready_;
  // 暂停静音门：置位时与预缓冲走同一条静音分支——不消费 ring、不计欠载、
  // 不推进 played。区别于停泵/重启，接口 claim 与采样率协商全程不受影响，
  // 这正是绕开"每次暂停恢复都重发 SET_CUR 触发设备重锁 PLL"问题的关键。
  const bool muted = muted_.load(std::memory_order_relaxed);
  const bool silence_only = prebuffering || muted;

  uint32_t total_bytes = 0;
  uint32_t total_frames = 0;
  bool underrun = false;
  for (int p = 0; p < kNumPackets; p++) {
    // 本包帧数 = 累加器跑 ticks_per_packet 次；长期平均严格等于采样率
    uint32_t frames = 0;
    for (int t = 0; t < ticks_per_packet_; t++) {
      uframe_acc_ += frames_per_uframe_q16_;
      frames += static_cast<uint32_t>(uframe_acc_ / kQ16Unit);
      uframe_acc_ %= kQ16Unit;
    }
    uint32_t bytes = frames * bytes_per_frame_;
    if (bytes > static_cast<uint32_t>(max_packet_size_)) {
      // 协商不一致：Kotlin 推导的参数应保证不会触发。钳位并重算帧数。
      LOGE("refill packet %d: bytes=%u > max_packet=%d, clamping", p, bytes,
           max_packet_size_);
      bytes = static_cast<uint32_t>(max_packet_size_);
      frames = bytes / bytes_per_frame_;  // 向下取整
      bytes = frames * bytes_per_frame_;
    }
    if (total_bytes + bytes > capacity) {
      bytes = capacity - total_bytes;
      frames = bytes / bytes_per_frame_;
      bytes = frames * bytes_per_frame_;
    }
    if (silence_only) {
      // 预缓冲期或暂停静音门关闭：整包静音，不碰 ring，不计欠载，不累计已播放帧。
      memset(buffer + total_bytes, 0, bytes);
    } else {
      // 从 ring 拉数据；不足补零（保持总线不中断）
      uint32_t got = ring_read(buffer + total_bytes, bytes);
      if (got < bytes) {
        memset(buffer + total_bytes + got, 0, bytes - got);
        underrun = true;
      }
      // 输出增益：<100 时把读到的 16bit 小端样本线性衰减，给波峰留余量避免削顶。
      // 100 走无衰减快路径（真正的 bit-perfect）。只处理真实读到的 got 字节
      // （补零部分是 0，衰减后仍是 0，无需动）。用 int32 中间量避免乘法溢出。
      if (volume_percent < kVolumeUnity) {
        auto* samples = reinterpret_cast<int16_t*>(buffer + total_bytes);
        for (uint32_t s = 0; s < got / 2; s++) {
          samples[s] = static_cast<int16_t>(
              static_cast<int32_t>(samples[s]) * volume_percent / kVolumeUnity);
        }
      }
      total_frames += frames;
    }
    transfer->iso_packet_desc[p].length = bytes;
    total_bytes += bytes;
  }
  if (underrun) {
    underruns_.fetch_add(1, std::memory_order_relaxed);
  }
  return total_frames;
}

int UsbAudioDriver::read_signed_le16(const uint8_t* data) {
  const uint16_t raw = static_cast<uint16_t>(data[0]) |
                       (static_cast<uint16_t>(data[1]) << 8);
  return static_cast<int16_t>(raw);
}

void UsbAudioDriver::probe_hardware_volume() {
  hardware_volume_supported_ = false;
  if (uac_version_ != 0x0200 || feature_unit_id_ <= 0 || dev_ == nullptr) {
    LOGE("UAC2 hardware volume unavailable: feature_unit=%d", feature_unit_id_);
    return;
  }
  const uint32_t declared_mask = static_cast<uint32_t>(volume_channel_mask_);
  const uint32_t mask = (declared_mask & 1u) != 0 ? 1u : declared_mask;
  if (mask == 0) return;
  int reference_minimum = 0;
  int reference_maximum = 0;
  int reference_resolution = 0;
  bool have_reference = false;
  for (int channel = 0; channel < 32; ++channel) {
    if ((mask & (uint32_t{1} << channel)) == 0) continue;
    uint8_t data[8] = {0};
    const uint16_t w_index = static_cast<uint16_t>((feature_unit_id_ << 8) | control_interface_number_);
    const uint16_t w_value = static_cast<uint16_t>(0x0200 | channel);
    const int rc = libusb_control_transfer(dev_, 0xA1, 0x02, w_value, w_index, data, sizeof(data), 1000);
    if (rc < 8) {
      LOGE("UAC2 hardware volume GET_RANGE channel=%d rc=%d", channel, rc);
      return;
    }
    const int sub_ranges = static_cast<int>(data[0]) | (static_cast<int>(data[1]) << 8);
    const int minimum = read_signed_le16(data + 2);
    const int maximum = read_signed_le16(data + 4);
    const int resolution = read_signed_le16(data + 6);
    if (sub_ranges != 1 || minimum > maximum || resolution <= 0) return;
    if (!have_reference) {
      reference_minimum = minimum;
      reference_maximum = maximum;
      reference_resolution = resolution;
      have_reference = true;
    } else if (minimum != reference_minimum || maximum != reference_maximum || resolution != reference_resolution) {
      LOGE("UAC2 hardware volume range mismatch channel=%d", channel);
      return;
    }
  }
  if (!have_reference) return;
  hardware_volume_min_db256_ = reference_minimum;
  hardware_volume_max_db256_ = reference_maximum;
  hardware_volume_step_db256_ = reference_resolution;
  hardware_volume_supported_ = true;
  LOGE("UAC2 hardware volume supported unit=%d mask=0x%08x min=%d max=%d step=%d", feature_unit_id_, mask, reference_minimum, reference_maximum, reference_resolution);
}
int UsbAudioDriver::set_sample_rate(int sample_rate) {
  uint8_t data[4] = {0, 0, 0, 0};
  data[0] = static_cast<uint8_t>(sample_rate & 0xFF);
  data[1] = static_cast<uint8_t>((sample_rate >> 8) & 0xFF);
  data[2] = static_cast<uint8_t>((sample_rate >> 16) & 0xFF);
  // UAC1 用 3 字节，UAC2 用 4 字节（24bit 宽不过 3 字节可用，但保留 4 字节长度避免某些设备拒绝）。
  const int data_len = (uac_version_ == 0x0200) ? 4 : 3;

  if (uac_version_ == 0x0200) {
    // UAC2：采样率控制挂在 Clock Source 实体上，收件人是 Interface。
    // bmRequestType = 0x21 = CLASS | INTERFACE | HOST_TO_DEVICE
    // wValue = CS_SAM_FREQ_CONTROL(0x01) << 8
    // wIndex = (clockId << 8) | controlInterfaceNumber
    const uint16_t w_index = static_cast<uint16_t>(
        (clock_id_ << 8) | control_interface_number_);
    int rc = libusb_control_transfer(dev_, 0x21, 0x01, 0x0100, w_index, data, data_len, 1000);
    LOGE("UAC2 SET_CUR sample rate=%d clock=%d control_interface=%d wIndex=0x%04x rc=%d",
         sample_rate, clock_id_, control_interface_number_,
         static_cast<unsigned int>(w_index), rc);
    if (rc < 0) return rc;
    return (rc == data_len) ? 0 : LIBUSB_ERROR_OTHER;
  }

  // UAC1：采样率控制挂在音频数据端点上，收件人是 Endpoint。
  // bmRequestType = 0x22 = CLASS | ENDPOINT | HOST_TO_DEVICE
  // wValue = CS_SAM_FREQ_CONTROL(0x01) << 8
  // wIndex = endpointAddress
  int rc = libusb_control_transfer(dev_, 0x22, 0x01, 0x0100,
                                   static_cast<uint16_t>(endpoint_address_), data, data_len, 1000);
  LOGE("UAC1 SET_CUR sample rate=%d endpoint=0x%02x rc=%d", sample_rate,
       static_cast<unsigned int>(endpoint_address_), rc);
  if (rc < 0) return rc;
  return (rc == data_len) ? 0 : LIBUSB_ERROR_OTHER;
}

int UsbAudioDriver::write(const uint8_t* data, int len) {
  if (!pumping_.load(std::memory_order_acquire) || ring_size_ == 0 || len <= 0) {
    return 0;
  }
  // 只接受整帧字节数：ring 数据必须按帧对齐，否则 refill 会写出半个帧。
  if (bytes_per_frame_ <= 0) return 0;  // 防御：参数异常直接不写（正常不会触发）
  uint32_t w = ring_write_.load(std::memory_order_acquire);
  uint32_t r = ring_read_.load(std::memory_order_acquire);
  uint32_t used = w - r;
  uint32_t free_bytes = ring_size_ - used;
  if (free_bytes <= 1) return 0;  // 保留 1 字节，避免满/空难分
  // 先按帧对齐裁剪长度，再取可用空间的最小值。
  const uint32_t frame_bytes = static_cast<uint32_t>(bytes_per_frame_);
  uint32_t n = std::min<uint32_t>(free_bytes - 1, static_cast<uint32_t>(len));
  n -= n % frame_bytes;
  if (n == 0) return 0;
  const uint32_t first = std::min(n, ring_size_ - (w & ring_mask_));
  if (first > 0) std::memcpy(ring_.data() + (w & ring_mask_), data, first);
  if (n > first) std::memcpy(ring_.data(), data + first, n - first);
  w += n;
  ring_write_.store(w, std::memory_order_release);
  return static_cast<int>(n);
}

void UsbAudioDriver::flush() {
  // 不再直接写 ring_read_（事件线程独占）。改为记下"写位置"让事件线程在
  // 下次 COMPLETED 回调中安全地重置读指针。
  const uint32_t w = ring_write_.load(std::memory_order_acquire);
  pending_flush_.store(w, std::memory_order_release);
}

uint32_t UsbAudioDriver::ring_read(uint8_t* out, uint32_t len) {
  uint32_t r = ring_read_.load(std::memory_order_acquire);
  uint32_t w = ring_write_.load(std::memory_order_acquire);
  const uint32_t available = w - r;
  if (available == 0) return 0;
  const uint32_t n = std::min(available, len);
  const uint32_t first = std::min(n, ring_size_ - (r & ring_mask_));
  if (first > 0) std::memcpy(out, ring_.data() + (r & ring_mask_), first);
  if (n > first) std::memcpy(out + first, ring_.data(), n - first);
  r += n;
  ring_read_.store(r, std::memory_order_release);
  return n;
}

}  // namespace pulsar
