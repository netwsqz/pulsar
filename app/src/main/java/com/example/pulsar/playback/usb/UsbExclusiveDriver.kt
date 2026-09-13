package com.example.pulsar.playback.usb

/**
 * [LibusbAudioSink] 使用的设备级驱动门面。设备句柄由 controller 所有，sink 只负责
 * 在渲染线程启动流、写入 PCM 和清空 ring。
 */
interface UsbExclusiveDriver {
    val isOpen: Boolean
    val isStreaming: Boolean
    val format: UsbAudioFormat?
    val isHighSpeed: Boolean
    val sampleRate: Int?

    fun playedFrames(): Long

    /**
     * 事件线程心跳计数：native 事件循环每轮 +1，只增。
     *
     * 与 [playedFrames] 一起喂给 [IsoPumpWatchdog]，用来区分两种「播放帧不推进」：
     * 心跳在涨 = 线程有被调度、泵真卡死（要回退）；心跳也停 = 线程被 CPU 饿住
     * （等着自愈，别误触发 flushRing + 重启流造成断音）。
     */
    fun heartbeat(): Long

    /**
     * 泵是否已确定不再运行（native 事件循环退出，或设备句柄已关闭）。
     * 为 true 时看门狗立即判死回退，不必再等推进窗口。
     */
    fun loopExited(): Boolean

    fun underruns(): Long
    fun pendingFrames(): Long
    fun write(bytes: ByteArray): Int
    fun flushRing()
    /**
     * 以指定采样率/位深/声道启动流。位深与声道用于在设备可用格式里协商匹配的格式
     * （换 alt 不重新 claim 接口）；无匹配格式时返回错误码，由 sink 回退系统输出。
     */
    fun startStream(sampleRate: Int, bitsPerSample: Int, channels: Int): Int
    fun stopStream()
    fun setVolume(percent: Int)

    /**
     * 暂停用"静音门"：不停泵、不触碰接口/控制传输，只是让 refill() 送静音。
     * 见 [UsbNativeDriver.nativeSetMuted] 的详细动机说明。
     */
    fun setMuted(muted: Boolean)
}
