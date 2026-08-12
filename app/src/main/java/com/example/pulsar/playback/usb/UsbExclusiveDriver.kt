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
