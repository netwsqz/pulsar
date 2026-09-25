package com.example.pulsar.playback

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 回放增益（ReplayGain）sink：把上游 PCM 读出、按当前曲目的线性增益缩放到**自己造的
 * 缓冲**里，再交给 delegate（USB 独占的 `LibusbAudioSink` 或系统输出的 `DefaultAudioSink`）。
 * 两条输出链路都会经过它，行为一致。
 *
 * **为什么必须拷贝，而不是就地改**：
 * - Media3 交给 sink 的是**解码器输出缓冲，只读**（`MediaCodec.getOutputBuffer` 的契约），
 *   原地写会抛 `ReadOnlyBufferException`；
 * - 也不能挂 `DefaultAudioSink.Builder.setAudioProcessors`：Media3 1.5.1 的 `configure()`
 *   只在**非 float** 分支里把自定义处理器链加进管线（float 分支只用
 *   `[ToFloatPcmAudioProcessor]`，见其字节码），而本项目 float 输出是常态——挂上去会
 *   静默不生效。
 *
 * **为什么每一帧都拷贝，即使增益是 1**（不图"零拷贝快路径"那一次 memcpy）：
 * `DefaultAudioSink.handleBuffer` 开头断言 `inputBuffer == null || buffer == inputBuffer`
 * —— 它没吃完的缓冲，下一次必须把**同一个对象**再交给它。任何"按当帧增益决定交哪个对象"
 * 的分支，只要在它攥着缓冲的窗口里翻转，就会换对象、撞断言、播放停止。拨开关和换歌都会
 * 造成这种翻转。统一走 [scratch] 就只有一个对象，从根上不可能违反。代价是每帧多一次
 * memcpy（约每秒 1MB 量级），换来的是不会在这条路上再出问题。
 *
 * 另外两条同样由 `DefaultAudioSink` 断言强制的约束：
 * - 交给它的缓冲**必须声明小端**（`order() == LITTLE_ENDIAN`），而 `ByteBuffer.allocateDirect()`
 *   默认是**大端**，所以分配时就要设；
 * - 上游永远只被"读"（`duplicate()` 读，一个字节不改）；delegate 没吃完的部分把上游
 *   position 退回去，ExoPlayer 按契约重投同一 buffer，下一轮把剩余部分重新拷进 [scratch]
 *   头部再交付——源数据始终是原始的，所以每个样本恰好乘一次增益。
 *
 * 已知取舍：增益在"某个 buffer 交付到一半"时变化的话，这个 buffer 的前后两段会用不同
 * 增益（窗口只有几毫秒，且只在拨开关/换歌那一瞬）。
 *
 * 包在 [SilenceSkippingAudioSink] 内侧：电平表先跑，读到的是增益前的电平，静音跳曲的
 * 阈值判定不会因为开了回放增益而漂移。
 */
@UnstableApi
class LoudnessGainAudioSink(
    delegate: AudioSink,
    private val provider: LoudnessGainProvider,
) : ForwardingAudioSink(delegate) {

    private var pcmEncoding: Int = -1

    /** 复用同一块直接缓冲，避免每个 buffer 都在音频线程上分配。 */
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)

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
        val start = buffer.position()
        val size = buffer.remaining()

        // 必须声明小端（DefaultAudioSink 的断言）。clear() 不改字节序，所以只在分配时设一次。
        // 续传期间 size 恒等于 scratch 里还没交付的量、必 ≤ capacity，不会换对象。
        if (scratch.capacity() < size) {
            scratch = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        }
        scratch.clear()
        // duplicate() 读，不动上游的 position/limit；缩放全程只碰我们自己的 scratch。
        scratch.put(buffer.duplicate())
        scratch.flip()
        applyGainInPlace(scratch, pcmEncoding, provider.linearGain)

        super.handleBuffer(scratch, presentationTimeUs, encodedAccessUnitCount)

        // delegate 吃下多少，上游就"消费"多少。没吃完的留在原位，ExoPlayer 会原样重投，
        // 下一轮从那里接着拷——源数据没被改过，所以不会重复乘增益。
        // 返回值只看上游是否还有剩余：delegate 返回 false 但已全部吃下时，数据其实
        // 已经交付完毕，报 true 才对（LibusbAudioSink 里同类的「误报已消费」坑）。
        buffer.position(start + size - scratch.remaining())
        return !buffer.hasRemaining()
    }

    override fun reset() {
        super.reset()
        pcmEncoding = -1
    }
}
