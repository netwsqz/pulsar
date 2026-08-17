package com.example.pulsar.playback.usb

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.example.pulsar.playback.PlaybackDiagnostics
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.update

/**
 * 将整数 PCM 直接送入 libusb 等时泵的 Media3 音频 sink。
 *
 * delegate 始终保持可用：USB 未就绪、格式不支持或等时泵卡死时，所有操作回退到
 * Media3 默认 sink。泵只在配置或首次 buffer 到达时启动，不在暂停时 stop，避免重新
 * claim 接口造成 EBUSY。
 */
@UnstableApi
class LibusbAudioSink(
    delegate: AudioSink,
    private val driver: UsbExclusiveDriver,
) : ForwardingAudioSink(delegate) {

    private var configuredFormat: Format? = null
    private var bypassActive = false
    private var hasChannelMapping = false
    private var pcmBytesPerFrame = 0
    private var inputBytesPerFrame = 0
    private var outputBitsPerSample = 0
    private var inputIsFloat = false
    private var framesWritten = 0L
    private var playedFramesBase = 0L
    private var startTimeUs = C.TIME_UNSET
    private var eosRequested = false
    private var lastEngageFailHash = 0
    private var pausedByUser = false
    // 暂停瞬间（flushRing/stopStream 之前）冻结的播放位置。flushRing 会重置 driver
    // 内部的播放帧基准，之后再用 driver.playedFrames() 算位置会得到错误结果——暂停期间
    // UI 仍按 500ms 轮询 getCurrentPositionUs，必须冻结返回这个值，否则进度条会突然
    // 回退到本次 bypass 段开始时的位置。C.TIME_UNSET 表示当前不在"用户暂停"状态。
    private var pausedPositionUs = C.TIME_UNSET
    private val watchdog = IsoPumpWatchdog(ISO_WARMUP_NS, ISO_STALL_NS)

    override fun getFormatSupport(format: Format): Int {
        Log.i(TAG, "getFormatSupport called: pcmEncoding=${format.pcmEncoding}")
        // USB 泵支持 16/24bit 整数 PCM。DefaultAudioSink 对 24bit 默认返回
        // SINK_FORMAT_SUPPORTED_WITH_TRANSCODING（因 enableFloatOutput=false），
        // 导致 MediaCodecAudioRenderer 把解码器输出的 24bit 降成 16bit 再喂给 sink
        // （真机日志证实：decoder output pcmEncoding=21=24bit，但 engage 收到 16bit）。
        // 这里声明支持 24bit，让 24bit 源以原生位深直达 USB 泵。
        return if (format.pcmEncoding == C.ENCODING_PCM_24BIT) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            super.getFormatSupport(format)
        }
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        // 诊断：确认 MediaCodecAudioRenderer 实际传给 sink 的格式。C.ENCODING_PCM_24BIT=21。
        Log.i(
            TAG,
            "configure: pcmEncoding=${inputFormat.pcmEncoding} " +
                "(C_24BIT=${C.ENCODING_PCM_24BIT}, C_16BIT=${C.ENCODING_PCM_16BIT}) " +
                "rate=${inputFormat.sampleRate} ch=${inputFormat.channelCount}",
        )
        configuredFormat = inputFormat
        hasChannelMapping = outputChannels != null
        bypassActive = false
        eosRequested = false
        framesWritten = 0L
        startTimeUs = C.TIME_UNSET
        lastEngageFailHash = 0
        pausedByUser = false
        pausedPositionUs = C.TIME_UNSET
        watchdog.reset(SystemClock.elapsedRealtimeNanos())

        val inputBits = pcmBitsFromEncoding(inputFormat.pcmEncoding)
        inputIsFloat = inputFormat.pcmEncoding == C.ENCODING_PCM_FLOAT
        inputBytesPerFrame = if (inputFormat.channelCount > 0 && inputBits > 0) {
            (inputBits + 7) / 8 * inputFormat.channelCount
        } else {
            0
        }
        outputBitsPerSample = inputBits
        if (inputIsFloat) {
            // float 本身没有 USB 位深；优先尝试 24bit，失败后由 engage 回退到 16bit。
            outputBitsPerSample = PREFERRED_FLOAT_OUTPUT_BITS
        }
        pcmBytesPerFrame = outputFrameBytes(inputFormat.channelCount, outputBitsPerSample)
        if (inputIsFloat && driver.format == null) {
            pcmBytesPerFrame = 0
        }
        if (driver.format?.let { it.channels == inputFormat.channelCount && it.bitsPerSample == outputBitsPerSample &&
                it.subframeSize > 0 && it.subframeSize != (outputBitsPerSample + 7) / 8 } == true) {
            // 当前转换器只输出 packed PCM；容器宽度不同的格式不能安全直送。
            pcmBytesPerFrame = 0
        }
        if (pcmBytesPerFrame > 0 && inputBytesPerFrame > 0 && driver.isOpen && !hasChannelMapping) {
            engage(inputFormat.sampleRate, inputFormat.channelCount, outputBitsPerSample)
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!bypassActive && !hasChannelMapping) tryLazyEngage()
        if (!bypassActive) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (!buffer.hasRemaining()) return true
        if (startTimeUs == C.TIME_UNSET) {
            anchorTo(presentationTimeUs)
        } else {
            // 与 DefaultAudioSink 的 200ms 阈值一致：codec 复用的自然切歌不会触发
            // flush/handleDiscontinuity，靠 PTS 与锚点+已写帧的偏差检测重锚定，
            // 否则位置会沿用旧曲坐标系推进（进度条不归零）。
            val rate = positionSampleRate()
            if (rate > 0) {
                val expectedUs = startTimeUs + framesWritten * 1_000_000L / rate
                if (Math.abs(presentationTimeUs - expectedUs) > POSITION_RESYNC_THRESHOLD_US) {
                    anchorTo(presentationTimeUs)
                }
            }
        }

        val frameBytes = inputBytesPerFrame
        if (frameBytes <= 0 || outputBitsPerSample <= 0) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val frameCount = buffer.remaining() / frameBytes
        if (frameCount <= 0) return !buffer.hasRemaining()

        val output = ByteArray(frameCount * pcmBytesPerFrame)
        val originalPosition = buffer.position()
        val writtenBytes = if (inputIsFloat) {
            PcmFloatConverter.convert(buffer, output, outputBitsPerSample)
        } else {
            buffer.get(output)
            buffer.position(originalPosition)
            output.size
        }
        if (writtenBytes <= 0) return !buffer.hasRemaining()

        val acceptedBytes = driver.write(if (writtenBytes == output.size) output else output.copyOf(writtenBytes))
        val writtenFrames = if (acceptedBytes > 0) acceptedBytes / pcmBytesPerFrame else 0
        if (writtenFrames > 0) {
            buffer.position(originalPosition + writtenFrames * frameBytes)
            framesWritten += writtenFrames
            watchdog.onWrite(SystemClock.elapsedRealtimeNanos())
        }
        // 暂停期间泵仍在跑（见 pause()：静音门而非停泵），playedFrames 因静音门
        // 不计入而冻结不再推进，这是预期行为，不能喂给看门狗——否则会被误判为
        // "泵卡死"并强制回退系统音频。
        if (!pausedByUser) checkWatchdog()
        return !buffer.hasRemaining()
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!bypassActive) return super.getCurrentPositionUs(sourceEnded)
        // 暂停期间冻结位置：不能用 computePositionUs()——flushRing 已经重置了 driver
        // 侧的播放帧基准，此时重新计算会得到错误（回退）的结果。
        if (pausedByUser) return pausedPositionUs
        return computePositionUs()
    }

    /** 以指定 PTS 为新坐标系原点重新锚定，丢弃 ring 中旧曲的尾巴数据。 */
    private fun anchorTo(presentationTimeUs: Long) {
        driver.flushRing()
        framesWritten = 0L
        playedFramesBase = driver.playedFrames()
        startTimeUs = presentationTimeUs
    }

    override fun handleDiscontinuity() {
        super.handleDiscontinuity()
        // 切歌（onProcessedStreamChange）通知：Media3 契约要求 sink 在下一个
        // buffer 到达时重新锚定位置坐标系（对齐 DefaultAudioSink 的
        // startMediaTimeUsNeedsSync），否则进度条沿用旧曲坐标继续推进。
        if (bypassActive) {
            driver.flushRing()
            framesWritten = 0L
            playedFramesBase = driver.playedFrames()
            startTimeUs = C.TIME_UNSET
        }
    }

    /** 计算位置用的采样率：USB 协商出的实际速率优先（playedFrames 按它推进）。 */
    private fun positionSampleRate(): Int {
        val usbRate = driver.sampleRate
        if (usbRate != null && usbRate > 0) return usbRate
        return configuredFormat?.sampleRate ?: 0
    }

    private fun computePositionUs(): Long {
        val rate = positionSampleRate()
        if (rate <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
        if (startTimeUs == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET
        val playedUs = (driver.playedFrames() - playedFramesBase).coerceAtLeast(0L) * 1_000_000L / rate
        return startTimeUs + playedUs
    }

    override fun hasPendingData(): Boolean {
        if (!bypassActive) return super.hasPendingData()
        return driver.pendingFrames() > 0
    }

    override fun playToEndOfStream() {
        super.playToEndOfStream()
        eosRequested = true
        stopAfterDrainIfNeeded()
    }

    override fun isEnded(): Boolean {
        if (!bypassActive) return super.isEnded()
        stopAfterDrainIfNeeded()
        return eosRequested && !hasPendingData() && !driver.isStreaming
    }

    private fun stopAfterDrainIfNeeded() {
        if (bypassActive && eosRequested && !hasPendingData() && driver.isStreaming) {
            driver.stopStream()
        }
    }

    override fun pause() {
        super.pause()
        if (bypassActive) {
            // 暂停不再 stop_stream/start_stream——那两个操作会重发 UAC2 SET_CUR
            // 采样率控制传输并重触发 300ms 静音预缓冲，原本只为切歌/换格式设计。
            // 部分廉价 UAC2 小尾巴收到 SET_CUR 后会短暂重锁 PLL/静音，套用到每次
            // 暂停/恢复上就是断续的真正来源（真机验证确认）。改用静音门：泵、接口
            // claim、ring 内容全部保持不变，只是 native refill() 改送静音。
            // ExoPlayer 暂停期间仍会继续调用 handleBuffer 做预解码，write() 照常
            // 把数据推进 ring（这正是恢复时需要续播的数据，无需重新预缓冲）。
            pausedByUser = true
            pausedPositionUs = computePositionUs()
            Log.i(TAG, "USB sink pause: 静音门 (泵保持运行)")
            driver.setMuted(true)
        }
    }

    override fun play() {
        super.play()
        pausedByUser = false
        if (bypassActive) {
            Log.i(TAG, "USB sink play: 解除静音门")
            driver.setMuted(false)
            // playedFrames 在静音期间冻结（native 不计入），必须重置基准，否则用暂停
            // 时冻结的位置作为新起点后会把这段冻结量重复计入，导致位置突然跳跃。
            // pausedPositionUs 可能已被 flush（seek）清空——那时锚点由新 buffer 的
            // PTS 重新建立，不得用 UNSET 把它覆盖掉。
            playedFramesBase = driver.playedFrames()
            if (pausedPositionUs != C.TIME_UNSET) startTimeUs = pausedPositionUs
            pausedPositionUs = C.TIME_UNSET
            // 看门狗在暂停期间没被喂（见 handleBuffer 的 pausedByUser 判断），
            // lastAdvanceNs 是暂停前的旧值；不重置会在恢复后立即被判定为"卡死太久"。
            watchdog.reset(SystemClock.elapsedRealtimeNanos())
        }
    }

    override fun flush() {
        super.flush()
        if (bypassActive) {
            driver.flushRing()
            framesWritten = 0L
            playedFramesBase = driver.playedFrames()
            startTimeUs = C.TIME_UNSET
            // seek 会经 onPositionReset → flush：暂停中的拖动进度条也走这条路。
            // 必须清掉暂停冻结，否则 getCurrentPositionUs 一直返回 seek 前的旧值、
            // play() 又把它写回锚点，进度条永久停在暂停位置（音频实际已跳）。
            pausedByUser = false
            pausedPositionUs = C.TIME_UNSET
        }
        lastEngageFailHash = 0
        watchdog.reset(SystemClock.elapsedRealtimeNanos())
    }

    override fun reset() {
        super.reset()
        if (driver.isStreaming) driver.stopStream()
        configuredFormat = null
        hasChannelMapping = false
        bypassActive = false
        pcmBytesPerFrame = 0
        inputBytesPerFrame = 0
        outputBitsPerSample = 0
        inputIsFloat = false
        framesWritten = 0L
        startTimeUs = C.TIME_UNSET
        lastEngageFailHash = 0
        watchdog.reset(SystemClock.elapsedRealtimeNanos())
    }

    override fun release() {
        super.release()
        if (driver.isStreaming) driver.stopStream()
    }

    private fun tryLazyEngage() {
        val format = configuredFormat ?: return
        if (!driver.isOpen || pcmBytesPerFrame <= 0 || inputBytesPerFrame <= 0) return
        val bits = outputBitsPerSample
        if (format.sampleRate <= 0 || format.channelCount <= 0 || bits <= 0) return
        engage(format.sampleRate, format.channelCount, bits)
    }

    private fun engage(sampleRate: Int, channels: Int, bits: Int) {
        if (sampleRate <= 0 || channels <= 0 || bits <= 0) return
        // 诊断：对比 ExoPlayer 实际输出格式 vs 我们选中的 USB 格式。若两者的声道/位深
        // 不一致，写入按 ExoPlayer 帧宽、读取按 USB 帧宽，字节流会整体错位 → ring 满、
        // 无欠载，但送 DAC 的是错位数据 = 从第一帧就失真。
        val usb = driver.format
        Log.i(
            TAG,
            "engage: ExoPlayer[rate=$sampleRate, ch=$channels, bits=$bits] vs " +
                "USB[ch=${usb?.channels}, bits=${usb?.bitsPerSample}, " +
                "subframe=${usb?.subframeSize}, sinkFrameBytes=$pcmBytesPerFrame]",
        )
        val formatHash = (sampleRate * 31 + channels) * 31 + bits
        if (formatHash == lastEngageFailHash) return
        var result = driver.startStream(sampleRate, bits, channels)
        if (result != 0 && inputIsFloat && bits == PREFERRED_FLOAT_OUTPUT_BITS) {
            for (fallbackBits in FALLBACK_FLOAT_OUTPUT_BITS) {
                if (fallbackBits == bits) continue
                val fallbackResult = driver.startStream(sampleRate, fallbackBits, channels)
                if (fallbackResult == 0 && isPackedUsbFormatCompatible(fallbackBits, channels)) {
                    outputBitsPerSample = fallbackBits
                    pcmBytesPerFrame = outputFrameBytes(channels, fallbackBits)
                    result = 0
                    Log.i(TAG, "float USB 输出回退到 ${fallbackBits}bit")
                    break
                }
                if (fallbackResult == 0) driver.stopStream()
                result = fallbackResult
            }
        }
        if (result == 0) {
            val activeBits = outputBitsPerSample
            bypassActive = true
            lastEngageFailHash = 0
            framesWritten = 0L
            playedFramesBase = driver.playedFrames()
            startTimeUs = C.TIME_UNSET
            eosRequested = false
            watchdog.reset(SystemClock.elapsedRealtimeNanos())
            // USB 独占时 delegate 的 AudioTrack 永不初始化（handleBuffer 全走泵、
            // 从不写 delegate），Media3 的 onAudioTrackInitialized 回调因此不触发，
            // UI 会一直显示"未播放"。这里把 ExoPlayer 实际送出的采样率/位深
            // 写进诊断单例。回退系统音频时走 delegate 正常路径，回调仍会覆盖。
            PlaybackDiagnostics.info.update {
                it.copy(
                    sampleRateHz = sampleRate,
                    encoding = when (activeBits) {
                        16 -> C.ENCODING_PCM_16BIT
                        24 -> C.ENCODING_PCM_24BIT
                        32 -> C.ENCODING_PCM_32BIT
                        else -> it.encoding
                    }
                )
            }
        } else {
            bypassActive = false
            lastEngageFailHash = formatHash
            Log.w(TAG, "USB 等时泵启动失败，回退系统音频输出: error=$result")
        }
    }

    private fun checkWatchdog() {
        if (!bypassActive) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (watchdog.check(now, driver.playedFrames())) {
            bypassActive = false
            driver.flushRing()
            if (driver.isStreaming) driver.stopStream()
            Log.w(TAG, "USB 等时泵无推进，回退系统音频输出")
        }
    }

    private fun isPackedUsbFormatCompatible(bits: Int, channels: Int): Boolean {
        val format = driver.format ?: return false
        return format.bitsPerSample == bits && format.channels == channels &&
            (format.subframeSize == 0 || format.subframeSize == (bits + 7) / 8)
    }

    private fun outputFrameBytes(channels: Int, bitsPerSample: Int): Int =
        if (channels > 0 && bitsPerSample > 0) {
            (bitsPerSample + 7) / 8 * channels
        } else {
            0
        }

    private fun pcmBitsFromEncoding(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT -> 32
        C.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }

    companion object {
        private const val TAG = "LibusbAudioSink"
        private const val ISO_WARMUP_NS = 400_000_000L
        private const val ISO_STALL_NS = 400_000_000L
        private const val PREFERRED_FLOAT_OUTPUT_BITS = 32
        private val FALLBACK_FLOAT_OUTPUT_BITS = intArrayOf(24, 16)

        /** PTS 与锚点+已写帧的偏差超过该值即重锚定（与 DefaultAudioSink 阈值一致）。 */
        private const val POSITION_RESYNC_THRESHOLD_US = 200_000L
    }
}
