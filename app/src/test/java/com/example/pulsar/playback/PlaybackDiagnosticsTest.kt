package com.example.pulsar.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 输出位深诊断的取值：UI 展示的是**源的真实位深**（解码器实际输出的 PCM 编码），
 * 既不是 AudioTrack 的编码（系统输出会被统一成 float32），也不是 USB 线宽
 * （独占路径按 32bit 子帧送，那是容器宽度）。所以压缩格式的「无值」必须挡掉。
 */
class PlaybackDiagnosticsTest {

    @Test
    fun `解码器输出的 16bit 编码原样采纳`() {
        assertEquals(C.ENCODING_PCM_16BIT, decoderEncodingForDiagnostics(C.ENCODING_PCM_16BIT))
    }

    @Test
    fun `解码器输出的 24bit 编码原样采纳`() {
        assertEquals(C.ENCODING_PCM_24BIT, decoderEncodingForDiagnostics(C.ENCODING_PCM_24BIT))
    }

    @Test
    fun `无 PCM 编码的格式不得写进诊断`() {
        // 压缩格式（aac/flac 的输入侧）没有 pcmEncoding，Media3 给 NO_VALUE(-1)；
        // 空格式给 ENCODING_INVALID(0)。不挡的话 UI 会显示「编码-1」。
        assertNull(decoderEncodingForDiagnostics(Format.NO_VALUE))
        assertNull(decoderEncodingForDiagnostics(C.ENCODING_INVALID))
    }
}
