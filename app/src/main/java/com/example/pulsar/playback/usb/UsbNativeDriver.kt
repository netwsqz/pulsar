package com.example.pulsar.playback.usb

/**
 * 等时传输泵的原生入口（JNI，对应 native 的 `UsbAudioDriver`）。
 *
 * 契约：
 * - [nativeOpen] 成功后返回非零原生句柄（Long）；失败返回 -1..-7 的错误码（[Error] 枚举）。
 * - **两阶段生命周期**：设备级 [nativeOpen]/[nativeClose] 各一次（wrap fd → claim →
 *   set alt）；流级 [nativeStartStream]/[nativeStopStream] 可反复调用，始终保留接口
 *   claim——切歌/换采样率不重抢接口，避免内核 snd-usb-audio 抢回导致 EBUSY 哑死。
 * - **fd 所有权归 Kotlin**：vendored libusb 1.0.27 的 fd_keep=1 使 libusb 从不关闭
 *   wrapped fd，native 也从不 close。调用方必须在 [nativeClose] 之后 close
 *   `UsbDeviceConnection`；[nativeOpen] 返回负错误码时同样要 close 连接。
 * - **线程**：[nativeWrite]/[nativeFlush] 必须由音频线程调用；[nativeClose]
 *   会阻塞等待事件线程退出，不能与其并发——播放停止/切歌流程必须先停写再
 *   close。nativeClose 返回后句柄失效，禁止再次调用（包括再次 nativeClose）。
 * - 时序与字节参数（ticksPerPacket / bytesPerFrame / framesPerUframeQ16）来自
 *   [UsbStreamParams.derive]，native 只按参数执行、不做格式决策。
 */
object UsbNativeDriver {

    init {
        System.loadLibrary("pulsar_usb")
    }

    /** 打开/启动失败的错误阶段，与 native `UsbAudioDriver::Error` 一一对应。 */
    enum class Error(val code: Int) {
        INIT(-1),   // libusb_init 失败
        WRAP(-2),   // libusb_wrap_sys_device 失败
        CLAIM(-3),  // claim_interface 失败（多半需开发者选项 Disable USB audio routing）
        SET_ALT(-4),
        SET_RATE(-5),
        ALLOC(-6),
        SUBMIT(-7),
    }

    /**
     * 设备级打开：包装 fd、claim 流接口、设初始 alt setting。
     *
     * @param controlInterfaceNumber UAC2 SET_CUR 采样率的 wIndex 低字节
     *   （AudioControl 接口号）；UAC1 不用，传 0。
     * @param featureUnitId UAC2 播放链路上的 Feature Unit 音量单元 ID；无则传 0。
     * @param volumeChannelMask UAC2 Feature Unit 中支持可写 Volume 的声道位掩码。
     */
    external fun nativeOpen(
        fd: Int,
        interfaceNumber: Int,
        altSetting: Int,
        endpointAddress: Int,
        uacVersion: Int,
        clockId: Int,
        controlInterfaceNumber: Int,
        featureUnitId: Int,
        volumeChannelMask: Int,
    ): Long

    /**
     * 流级启动：设为新采样率并启动等时泵。若已在流：先停泵（不释放接口），
     * 换 alt（若变）、换采样率，再重启。成功返回 0，失败返回负错误码（[Error]）。
     */
    external fun nativeStartStream(
        handle: Long,
        sampleRate: Int,
        ticksPerPacket: Int,
        bytesPerFrame: Int,
        framesPerUframeQ16: Long,
        maxPacketSize: Int,
        altSetting: Int,
    ): Int

    /** 流级停止：停泵，保留接口 claim 与句柄。幂等。 */
    external fun nativeStopStream(handle: Long)

    /** 推入 PCM 数据，返回实际接收的字节数（ring 满时小于 length）。 */
    external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): Int

    /** 丢弃尚未发出的 PCM（切歌/暂停），在途数据照常送出。 */
    external fun nativeFlush(handle: Long)

    /** 设备级关闭：停泵、释放接口、关闭句柄并销毁。幂等，阻塞直至事件线程退出。 */
    external fun nativeClose(handle: Long)

    /** 设备已实际消费的帧数（ExoPlayer 进度与看门狗）。 */
    external fun nativeGetPlayedFrames(handle: Long): Long

    /**
     * 事件线程心跳计数：事件循环每轮 +1，只增。
     *
     * 供 [IsoPumpWatchdog] 区分「泵卡死」（心跳在涨、播放帧不涨）与「CPU 饥饿」
     * （心跳也不涨）——后者会自愈，误判成卡死会触发 flushRing + 重启流导致断音。
     */
    external fun nativeGetHeartbeat(handle: Long): Long

    /** native 事件循环是否已退出（`event_loop` 因出错停泵或正常收尾）。 */
    external fun nativeIsLoopExited(handle: Long): Boolean

    /** 累计欠载次数（refill 补零的传输），排查用。 */
    external fun nativeGetUnderruns(handle: Long): Long

    /**
     * 设备真实链路速度是否为高速时序（每秒 8000 微帧）。native 在 [nativeOpen]
     * 时用 libusb_get_device_speed 探测。用于推导正确的每包帧数——按全速把 8 倍
     * 数据塞进高速微帧会导致设备掉线（LIBUSB_TRANSFER_NO_DEVICE）。
     */
    external fun nativeIsHighSpeed(handle: Long): Boolean

    /** 描述符声明且 GET_RANGE 返回合法范围时为 true。 */
    external fun nativeHasHardwareVolume(handle: Long): Boolean

    /** 返回 [min, max, resolution]，单位均为 1/256 dB；不支持时返回 null。 */
    external fun nativeGetHardwareVolumeRange(handle: Long): IntArray?

    /**
     * 设置输出增益百分比（0..100）。USB 独占绕过系统音量、满刻度直送，在大动态
     * 段会过载削顶（真机验证：半音量即消失）。100 = 原样直送（bit-perfect）；
     * 更低值把 16bit 样本线性衰减给波峰留余量。
     */
    external fun nativeSetVolume(handle: Long, percent: Int)

    /**
     * 暂停用"静音门"：true 时泵继续跑但只送静音，不消费 ring、不推进已播放帧；
     * 绝不触碰接口 claim、不重发 UAC2 SET_CUR 采样率控制传输、不重触发预缓冲。
     * 真机验证：每次暂停/恢复都走 nativeStopStream/nativeStartStream 会导致部分
     * 廉价 UAC2 小尾巴在重收 SET_CUR 时短暂重锁 PLL/静音，表现为断断续续；
     * 改用静音门后暂停/恢复不再触碰控制传输，问题消失。
     */
    external fun nativeSetMuted(handle: Long, muted: Boolean)
}
