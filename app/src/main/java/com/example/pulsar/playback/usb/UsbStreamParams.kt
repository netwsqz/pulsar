package com.example.pulsar.playback.usb

/**
 * 从协商好的格式 + 实际采样率推导 native 等时泵需要的流参数。
 *
 * native 驱动（[UsbNativeDriver]）只接收这些参数、不做格式决策——
 * 时序与字节布局全在 Kotlin 定（TDD 可测），native 仅按参数执行。
 */
data class NativeStreamParams(
    /** 每个等时包的累积器刻度数（IsoFrameAccumulator 累加次数）。 */
    val ticksPerPacket: Int,
    /** 每帧字节数 = 每声道每帧字节数 × 声道数。 */
    val bytesPerFrame: Int,
    /** 每刻度平均帧数的 16.16 定点值（framesPerMicroframeQ16）。 */
    val framesPerUframeQ16: Long,
    /** 单个等时包最多能装多少帧（maxPacketSize / bytesPerFrame）。 */
    val maxFramesPerPacket: Int,
)

object UsbStreamParams {

    /**
     * 推导 native 泵参数。
     *
     * subframeSize 未知（0，描述符未解析到）时按位深向上取整兜底：
     * 16bit→2 字节、24bit→3 字节、32bit→4 字节。
     */
    fun derive(format: UsbAudioFormat, sampleRate: Int, isHighSpeed: Boolean): NativeStreamParams {
        val subframe = if (format.subframeSize > 0) format.subframeSize else (format.bitsPerSample + 7) / 8
        val bytesPerFrame = subframe * format.channels
        val ticks = UsbIsoScheduler.uframesPerPacketInterval(format.bInterval, isHighSpeed)
        return NativeStreamParams(
            ticksPerPacket = ticks,
            bytesPerFrame = bytesPerFrame,
            framesPerUframeQ16 = UsbIsoScheduler.framesPerMicroframeQ16(
                sampleRate,
                UsbIsoScheduler.microframesPerSec(isHighSpeed),
            ),
            maxFramesPerPacket = UsbIsoScheduler.maxFramesPerPacket(format.maxPacketSize, bytesPerFrame),
        )
    }
}
