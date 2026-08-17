package com.example.pulsar.playback.usb

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LibusbAudioSink] 独占（bypass）模式下的播放位置坐标系测试。
 *
 * 对齐 Media3 DefaultAudioSink 的 startMediaTimeUs 语义：
 * - 切歌（handleDiscontinuity 或 PTS 漂移 > 200ms）必须重锚定，进度条归零到新曲坐标。
 * - seek（onPositionReset → flush）必须清掉暂停冻结，恢复播放不得回写到旧锚点。
 * - 锚点未定时返回 CURRENT_POSITION_NOT_SET（渲染器据此跳过更新，避免进度闪 0）。
 */
class LibusbAudioSinkTest {

    /** 48kHz 立体声 16bit：每帧 4 字节，100ms = 4800 帧。 */
    private val frames100Ms = 4800

    private fun testFormat() = Format.Builder()
        .setSampleRate(48_000)
        .setChannelCount(2)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .build()

    private fun pcmBuffer(frames: Int): ByteBuffer = ByteBuffer.allocate(frames * 4)

    private fun newSink(driver: FakeUsbExclusiveDriver = FakeUsbExclusiveDriver()) =
        LibusbAudioSink(FakeAudioSink(), driver).also {
            it.configure(testFormat(), 0, null)
        }

    @Test
    fun `锚点未定时返回 CURRENT_POSITION_NOT_SET 而非 0`() {
        val sink = newSink()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
    }

    @Test
    fun `切歌 handleDiscontinuity 后位置重锚定到新曲目坐标`() {
        val driver = FakeUsbExclusiveDriver()
        val sink = newSink(driver)
        sink.play()

        // 第一曲：1s 处写 100ms 数据，设备消费一半 → 位置 1.05s
        sink.handleBuffer(pcmBuffer(frames100Ms), 1_000_000L, 1)
        driver.advancePlayed(frames100Ms / 2L)
        assertEquals(1_050_000L, sink.getCurrentPositionUs(false))

        // 切歌：Media3 调 handleDiscontinuity（onProcessedStreamChange），
        // 新曲 PTS 从自身时间线开始（如 100ms 处）
        sink.handleDiscontinuity()
        sink.handleBuffer(pcmBuffer(frames100Ms), 100_000L, 1)
        assertEquals(100_000L, sink.getCurrentPositionUs(false))

        // 新曲数据被设备消费后，位置从新锚点继续推进（4800 帧 = 100ms）
        driver.advancePlayed(frames100Ms.toLong())
        assertEquals(200_000L, sink.getCurrentPositionUs(false))

        // 新曲连续 buffer 的 PTS 与锚点+已写帧一致，不得再次误重锚定
        sink.handleBuffer(pcmBuffer(frames100Ms), 200_000L, 1)
        assertEquals(200_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun `PTS 漂移超过 200ms 时无需 handleDiscontinuity 也重锚定`() {
        val driver = FakeUsbExclusiveDriver()
        val sink = newSink(driver)
        sink.play()

        sink.handleBuffer(pcmBuffer(frames100Ms), 1_000_000L, 1)
        driver.advancePlayed(frames100Ms / 2L)
        assertEquals(1_050_000L, sink.getCurrentPositionUs(false))

        // 无 handleDiscontinuity 通知，但新曲 PTS 与旧坐标系偏差 1s > 200ms
        sink.handleBuffer(pcmBuffer(frames100Ms), 100_000L, 1)
        assertEquals(100_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun `暂停中 seek 后恢复播放不得回退到冻结的旧位置`() {
        val driver = FakeUsbExclusiveDriver()
        val sink = newSink(driver)
        sink.play()

        sink.handleBuffer(pcmBuffer(frames100Ms), 1_000_000L, 1)
        driver.advancePlayed(frames100Ms / 2L)
        assertEquals(1_050_000L, sink.getCurrentPositionUs(false))

        // 用户暂停：位置冻结
        sink.pause()
        assertEquals(1_050_000L, sink.getCurrentPositionUs(false))

        // 暂停中拖进度条：seek → onPositionReset → flush（对应 Media3 契约）
        sink.flush()

        // flush 已重锚定（锚点待定），不得再返回冻结旧值
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))

        // 暂停期间预解码的新位置数据到达（PTS = seek 目标 2s）
        sink.handleBuffer(pcmBuffer(frames100Ms), 2_000_000L, 1)
        assertEquals(2_000_000L, sink.getCurrentPositionUs(false))

        // 恢复播放：不得把暂停时的冻结值（1.05s）写回锚点
        sink.play()
        assertEquals(2_000_000L, sink.getCurrentPositionUs(false))

        // 播放推进后位置从新锚点继续走
        driver.advancePlayed(frames100Ms.toLong())
        assertEquals(2_100_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun `暂停恢复（未 seek）仍从冻结位置续接`() {
        val driver = FakeUsbExclusiveDriver()
        val sink = newSink(driver)
        sink.play()

        sink.handleBuffer(pcmBuffer(frames100Ms), 1_000_000L, 1)
        driver.advancePlayed(frames100Ms / 2L)
        sink.pause()
        assertEquals(1_050_000L, sink.getCurrentPositionUs(false))

        sink.play()
        assertEquals(1_050_000L, sink.getCurrentPositionUs(false))

        driver.advancePlayed(frames100Ms / 2L)
        assertEquals(1_100_000L, sink.getCurrentPositionUs(false))
    }
}

/** 手写 Fake：绕过（bypass）激活后 delegate 不应再被触碰，全部 no-op。 */
private class FakeAudioSink : AudioSink {
    override fun setListener(listener: AudioSink.Listener) = Unit
    override fun supportsFormat(format: Format) = false
    override fun getFormatSupport(format: Format) = AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getCurrentPositionUs(sourceEnded: Boolean) = AudioSink.CURRENT_POSITION_NOT_SET
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = Unit
    override fun play() = Unit
    override fun handleDiscontinuity() = Unit
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int) = true
    override fun playToEndOfStream() = Unit
    override fun isEnded() = true
    override fun hasPendingData() = false
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
    override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
    override fun getSkipSilenceEnabled() = false
    override fun setAudioAttributes(audioAttributes: AudioAttributes) = Unit
    override fun getAudioAttributes() = AudioAttributes.DEFAULT
    override fun setAudioSessionId(audioSessionId: Int) = Unit
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit
    override fun enableTunnelingV21() = Unit
    override fun disableTunneling() = Unit
    override fun setVolume(volume: Float) = Unit
    override fun pause() = Unit
    override fun flush() = Unit
    override fun reset() = Unit
}

/** 手写 Fake：frameBytes 与测试格式（16bit/2ch = 4 字节每帧）耦合。 */
private class FakeUsbExclusiveDriver : UsbExclusiveDriver {
    override val isOpen = true
    override var isStreaming = false
        private set
    override val format = UsbAudioFormat(
        uacVersion = UsbAudioFormat.UAC_VERSION_2,
        interfaceNumber = 1,
        altSetting = 1,
        endpointAddress = 0x01,
        maxPacketSize = 196,
        bInterval = 1,
        bSynchAddress = 0,
        channels = 2,
        bitsPerSample = 16,
        sampleRates = listOf(48_000),
        subframeSize = 2,
    )
    override val isHighSpeed = true
    override var sampleRate: Int? = null
        private set

    private var played = 0L
    private var pending = 0L
    var muted = false
        private set

    override fun playedFrames() = played
    override fun underruns() = 0L
    override fun pendingFrames() = pending

    override fun write(bytes: ByteArray): Int {
        pending += bytes.size / 4L
        return bytes.size
    }

    override fun flushRing() {
        pending = 0
    }

    override fun startStream(sampleRate: Int, bitsPerSample: Int, channels: Int): Int {
        isStreaming = true
        this.sampleRate = sampleRate
        return 0
    }

    override fun stopStream() {
        isStreaming = false
    }

    override fun setVolume(percent: Int) = Unit

    override fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    /** 模拟设备消费（静音门期间设备不推进帧）。 */
    fun advancePlayed(frames: Long) {
        check(!muted) { "静音门期间设备不应消费帧" }
        played += frames
        pending = (pending - frames).coerceAtLeast(0L)
    }
}
