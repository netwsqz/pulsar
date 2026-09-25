package com.example.pulsar.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回放增益 sink。三条最要紧的性质：
 * 1. 上游（解码器输出）**只读**——只能读出到自己的缓冲里缩放，绝不能原地写；
 * 2. 下游没吃完时把上游位置退回去让它重投，且**重投不会重复乘增益**；
 * 3. 增益为 1 时零拷贝直通（功能关掉时不能有任何额外开销）。
 */
class LoudnessGainAudioSinkTest {

    private val provider = LoudnessGainProvider()

    private fun floatFormat() = Format.Builder()
        .setSampleRate(48_000)
        .setChannelCount(2)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .build()

    private fun newSink(delegate: RecordingAudioSink): LoudnessGainAudioSink =
        LoudnessGainAudioSink(delegate, provider).also { it.configure(floatFormat(), 0, null) }

    private fun readOnlyShorts(vararg samples: Short): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it) }
        buffer.flip()
        return buffer.asReadOnlyBuffer()
    }

    private fun shortsOf(bytes: ByteArray): List<Short> {
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(bytes.size / 2) { view.short }
    }

    /** 按绝对下标读整块内容，不受 position 影响。 */
    private fun allBytes(buffer: ByteBuffer): ByteArray {
        val view = buffer.duplicate()
        view.clear()
        return ByteArray(view.remaining()).also { view.get(it) }
    }

    @Test
    fun `只读输入缓冲也能缩放且不改动上游`() {
        val delegate = RecordingAudioSink()
        val sink = newSink(delegate)
        provider.linearGain = 0.5f
        val input = readOnlyShorts(16384, -16384)
        assertTrue("前提：解码器输出就是只读的", input.isReadOnly)
        val upstreamBefore = allBytes(input)

        val handled = sink.handleBuffer(input, 1_000_000L, 1)

        assertTrue(handled)
        assertEquals(listOf<Short>(8192, -8192), shortsOf(requireNotNull(delegate.received)))
        // 读走不算改：上游内容必须一个字节都没变
        assertArrayEquals(upstreamBefore, allBytes(input))
        // PTS 原样透传，不能因为中间加了一层就丢掉时间戳
        assertEquals(1_000_000L, delegate.lastPresentationTimeUs)
    }

    @Test
    fun `增益为 1 时内容原样交付`() {
        val delegate = RecordingAudioSink()
        val sink = newSink(delegate)

        sink.handleBuffer(readOnlyShorts(1000, -2000), 0L, 1)

        assertEquals(listOf<Short>(1000, -2000), shortsOf(requireNotNull(delegate.received)))
    }

    @Test
    fun `下游没吃完时上游位置退回去且重投只乘一次增益`() {
        val delegate = RecordingAudioSink()
        val sink = newSink(delegate)
        provider.linearGain = 0.5f
        val input = readOnlyShorts(16384, -16384)
        delegate.consumeBytes = 2 // 只吃下第一个样本

        val first = sink.handleBuffer(input, 0L, 1)

        assertFalse("还有数据没交付，必须报未处理完", first)
        assertEquals("上游只该消费被下游真正吃下的那 2 字节", 2, input.position())

        delegate.consumeBytes = Int.MAX_VALUE
        val second = sink.handleBuffer(input, 0L, 1)

        assertTrue(second)
        // 第二个样本这轮才缩放：0.5 × -16384 = -8192。
        // 若把增益重复乘一次会得到 -4096——这正是这条用例要挡的。
        assertEquals(listOf<Short>(-8192), shortsOf(requireNotNull(delegate.received)))
    }

    @Test
    fun `交给下游的缓冲声明为小端`() {
        val delegate = RecordingAudioSink()
        val sink = newSink(delegate)
        provider.linearGain = 0.5f

        sink.handleBuffer(readOnlyShorts(16384, -16384), 0L, 1)

        // DefaultAudioSink 会断言 order() == LITTLE_ENDIAN，而 allocateDirect 默认是大端；
        // 且管线里的处理器都按声明的字节序读样本，声明错了样本会整体字节颠倒。
        assertEquals(ByteOrder.LITTLE_ENDIAN, requireNotNull(delegate.lastBuffer).order())
    }

    @Test
    fun `增益从非 1 翻到 1 时下游仍收到同一个缓冲对象`() {
        val delegate = RecordingAudioSink()
        val sink = newSink(delegate)
        provider.linearGain = 0.5f
        val input = readOnlyShorts(16384, -16384)
        delegate.consumeBytes = 2

        sink.handleBuffer(input, 0L, 1) // 下游只吃一半，攥住了我们交出去的缓冲

        // 播放中拨开关、或换到一首没有响度数据的歌：增益变回 1
        provider.linearGain = 1f
        delegate.consumeBytes = Int.MAX_VALUE
        sink.handleBuffer(input, 0L, 1)

        // DefaultAudioSink 断言「没吃完的 buffer，下次必须传同一个对象」。
        assertEquals(2, delegate.buffers.size)
        assertSame(delegate.buffers[0], delegate.buffers[1])
    }

    @Test
    fun `增益从 1 翻到非 1 时下游仍收到同一个缓冲对象`() {
        // 上一条的镜像方向：先以"增益 1"把缓冲交出去并被攥住，再翻成非 1。
        // 若图省事在增益为 1 时走"把上游原样透传"的快路径，这里就会换成另一个对象、
        // 撞同一条断言。统一走 scratch 之后两个方向都不可能出现。
        val delegate = RecordingAudioSink()
        val sink = newSink(delegate)
        provider.linearGain = 1f
        val input = readOnlyShorts(16384, -16384)
        delegate.consumeBytes = 2

        sink.handleBuffer(input, 0L, 1)

        provider.linearGain = 0.5f
        delegate.consumeBytes = Int.MAX_VALUE
        sink.handleBuffer(input, 0L, 1)

        assertEquals(2, delegate.buffers.size)
        assertSame(delegate.buffers[0], delegate.buffers[1])
    }

    @Test
    fun `未 configure 时不改数据`() {
        val delegate = RecordingAudioSink()
        val sink = LoudnessGainAudioSink(delegate, provider)
        provider.linearGain = 0.5f

        sink.handleBuffer(readOnlyShorts(1000, -2000), 0L, 1)

        // 不知道编码就不动样本（缩放函数对不认识的编码一律原样放过）
        assertEquals(listOf<Short>(1000, -2000), shortsOf(requireNotNull(delegate.received)))
    }

    @Test
    fun `不认识的编码只拷贝不改数据`() {
        val delegate = RecordingAudioSink()
        val sink = LoudnessGainAudioSink(delegate, provider).also {
            it.configure(
                Format.Builder()
                    .setSampleRate(48_000)
                    .setChannelCount(2)
                    .setPcmEncoding(C.ENCODING_PCM_8BIT)
                    .build(),
                0,
                null
            )
        }
        provider.linearGain = 0.5f

        sink.handleBuffer(readOnlyShorts(16384, -16384), 0L, 1)

        assertEquals(listOf<Short>(16384, -16384), shortsOf(requireNotNull(delegate.received)))
    }
}

/**
 * 手写 Fake：记录下游实际收到的内容与时间戳，并可以只吃下前若干字节，
 * 用来复现 ExoPlayer「没消费完就重投同一个 buffer」的契约。
 */
private class RecordingAudioSink : AudioSink {
    /** 每次 handleBuffer 最多接收多少字节；默认全收。 */
    var consumeBytes: Int = Int.MAX_VALUE

    /** 最近一次收到的内容快照（scratch 会被复用，必须拷走）。 */
    var received: ByteArray? = null
        private set
    var lastBuffer: ByteBuffer? = null
        private set
    /** 每次调用收到的缓冲对象，按顺序记录——用于断言「重投必须是同一个对象」。 */
    val buffers = mutableListOf<ByteBuffer>()
    var lastPresentationTimeUs: Long = Long.MIN_VALUE
        private set

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val snapshot = buffer.duplicate()
        received = ByteArray(snapshot.remaining()).also { snapshot.get(it) }
        lastBuffer = buffer
        buffers += buffer
        lastPresentationTimeUs = presentationTimeUs
        buffer.position(buffer.position() + minOf(consumeBytes, buffer.remaining()))
        return !buffer.hasRemaining()
    }

    override fun setListener(listener: AudioSink.Listener) = Unit
    override fun supportsFormat(format: Format) = false
    override fun getFormatSupport(format: Format) = AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getCurrentPositionUs(sourceEnded: Boolean) = AudioSink.CURRENT_POSITION_NOT_SET
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = Unit
    override fun play() = Unit
    override fun handleDiscontinuity() = Unit
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
