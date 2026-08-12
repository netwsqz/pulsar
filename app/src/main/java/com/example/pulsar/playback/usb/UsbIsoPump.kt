package com.example.pulsar.playback.usb

/**
 * 等时传输泵的门面，由 [LibusbAudioSink] 在渲染线程驱动，实现包装
 * [UsbNativeDriver] 的流级 JNI（nativeStartStream / nativeWrite / nativeFlush /
 * nativeStopStream / nativeGetPlayedFrames）。
 *
 * 与 [UsbNativeDriver] 的契约保持一致：
 * - 泵的生命周期（start/stop）由 [UsbExclusiveController] 在设备/格式就绪时驱动，
 *   sink 只读 `isStreaming` 决定是否走旁路。
 * - [write] 返回实际接收的字节数（ring 满时 < 传入长度）；sink 按整帧计数。
 * - [stopStream] 只停泵、**不释放接口 claim**——切歌/换采样率时由 controller
 *   直接换参数重启，绝不 nativeClose。
 */
interface UsbIsoPump {

    /** 泵是否在跑（startStream 成功后为 true，stopStream 后为 false）。 */
    val isStreaming: Boolean

    /** 设备已实际消费的帧数（单调不减，flush 不清零）。 */
    fun playedFrames(): Long

    /** 推入 PCM 数据，返回实际接收的字节数；0 = ring 已满。 */
    fun write(bytes: ByteArray): Int

    /** 丢弃尚未发出的数据（暂停/切歌），在途数据照常送出。 */
    fun flushRing()

    /** 启动等时泵（native 会先设采样率再起 URBs）。返回 0 成功 / 负错误码。 */
    fun startStream(sampleRate: Int): Int

    /** 停止等时泵，保留接口 claim 与句柄。幂等。 */
    fun stopStream()
}
