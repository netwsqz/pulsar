package com.example.pulsar.playback

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * 只读电平、不改数据的音频 sink：包裹 delegate，在每次 [handleBuffer] 时计算当前
 * buffer 的电平（dBFS）写入 [monitor]，供 [com.example.pulsar.PlaybackService] 的
 * 轮询协程结合播放位置判定是否应触发静音跳曲。
 *
 * 不修改、不拦截任何数据，因此可以安全地包在 [LibusbAudioSink] 外层或内层，不影响
 * USB 独占路径的字节对齐逻辑。
 */
@UnstableApi
class SilenceSkippingAudioSink(
    delegate: AudioSink,
    private val monitor: SilenceLevelMonitor,
) : ForwardingAudioSink(delegate) {

    private var pcmEncoding: Int = -1

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        pcmEncoding = inputFormat.pcmEncoding
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        monitor.levelDb = computeLevelDb(buffer, pcmEncoding)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun flush() {
        super.flush()
        monitor.levelDb = SILENT_FLOOR_DB
    }

    override fun reset() {
        super.reset()
        pcmEncoding = -1
        monitor.levelDb = SILENT_FLOOR_DB
    }
}
