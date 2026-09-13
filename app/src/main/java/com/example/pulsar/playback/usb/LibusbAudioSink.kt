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
    // 重锚瞬间 ring 中的积压帧数。漂移检测的"预期 PTS"必须包含它：重锚把坐标系
    // 拨回"新 PTS − 积压时长"后，ring 里积压的旧内容仍要先播完，随后写入的连续
    // buffer 的 PTS 只比"锚点 + 积压 + 已写帧"略超一步。漏掉积压项时，满缓冲场景
    // 下每个 buffer 都偏差 ≈ 积压时长 → 无限重锚风暴（真机日志：105 秒重锚 1 万+次，
    // 表现为进度抖动 + 杂音）。
    private var backlogFramesAtAnchor = 0L
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
        // 同格式切歌复用：Media3 每次切歌（新媒体源）都会重新 configure。若 bypass
        // 已激活且采样率/位深/声道与当前流完全一致，重启泵（engage → startStream）
        // 毫无必要——native start_stream() 会重置 ring 读写指针，把旧曲未播的
        // 尾部（ring ≈1s + 120ms 在途）整段丢弃 = 听感"歌尾被切"。此时保留 bypass
        // 与位置坐标系（新曲 PTS 到达时由 200ms 偏差检测做锚点平移，见 handleBuffer），
        // 旧曲尾部播完新曲数据无缝续上。仅格式变化时才走完整重置 + engage。
        val reuseBypass = bypassActive && driver.isStreaming &&
            configuredFormat?.let {
                it.sampleRate == inputFormat.sampleRate &&
                    it.channelCount == inputFormat.channelCount &&
                    it.pcmEncoding == inputFormat.pcmEncoding
            } == true
        configuredFormat = inputFormat
        hasChannelMapping = outputChannels != null
        bypassActive = reuseBypass
        eosRequested = false
        if (!reuseBypass) {
            framesWritten = 0L
            startTimeUs = C.TIME_UNSET
        }
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
        if (!reuseBypass && pcmBytesPerFrame > 0 && inputBytesPerFrame > 0 &&
            driver.isOpen && !hasChannelMapping
        ) {
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
            // 对齐 DefaultAudioSink 的 startMediaTimeUs 机制：自然切歌（同格式 codec
            // 重建/新媒体源）不触发 flush，靠 PTS 与锚点+已写帧的偏差检测新曲坐标
            // 系。检测到偏差 > 200ms 时做"补偿重锚"：新锚点 = 新曲 PTS − ring 中
            // 未播尾部的时长（见 anchorTo）。ring 不清空，旧曲尾部继续播完，进度条
            // 平滑跨过接缝，位置全程准确。
            val rate = positionSampleRate()
            if (rate > 0) {
                // 预期 PTS = 锚点 + 重锚时 ring 积压 + 此后已写帧。补偿式重锚把
                // 坐标系拨到"新 PTS − 积压时长"，ring 里的积压旧内容要先播完，
                // 因此连续 buffer 的 PTS 相对锚点天然领先积压量，不记就会每次都
                // "偏差 ≈ 积压" → 无限重锚（满缓冲场景必现，真机已复现）。
                val expectedUs = startTimeUs + (backlogFramesAtAnchor + framesWritten) * 1_000_000L / rate
                if (Math.abs(presentationTimeUs - expectedUs) > POSITION_RESYNC_THRESHOLD_US) {
                    // 播放中途 PTS 偏差超过 200ms = 上游（解码器/数据源）丢过内容，
                    // 正常播放极少出现（真机验证：一轮 6 分钟仅零星几次），记 info
                    // 留排查线索即可。
                    Log.i(TAG, "PTS 重锚: 偏差=${(presentationTimeUs - expectedUs) / 1000}ms")
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
        // 读指针必须**无条件**拨回「真正被接收的帧数」处。float 路径的
        // PcmFloatConverter.convert 已经把 buffer.position 推到末尾，若这里只在
        // writtenFrames > 0 时拨回，ring 恰好满（write 返回 0，一帧都塞不进）时
        // position 就停在 limit → hasRemaining()==false → 本函数误报「整个 buffer
        // 已消费」，ExoPlayer 直接丢弃这段音频 = 听感偶发「突然加快」（内容被跳过）。
        // 真机 ring 长期 ~99.8% 满，该分支随机命中；非 float 路径本身有显式还原，
        // 所以只在 float（本项目 ExoPlayer 输出的常态）下暴露。
        // writtenFrames=0 时等价于回到原位，ExoPlayer 会原样重投，一个字节不丢。
        buffer.position(originalPosition + writtenFrames * frameBytes)
        if (writtenFrames > 0) {
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

    /**
     * 以指定 PTS 为新坐标系原点重锚定，ring 不清空。ring 中还有旧曲未播的尾部时，
     * 这些帧会在新锚点生效后继续从 DAC 出声——锚点必须补偿这段时长：新锚点 =
     * PTS − 未播尾部时长。效果：进度条从"接缝点"平滑推进——尾部播完时位置恰好
     * 走到新曲 PTS，之后与新曲实际出声严格同步（位置始终等于扬声器正在放的内容）。
     */
    private fun anchorTo(presentationTimeUs: Long) {
        val rate = positionSampleRate()
        val pendingFrames = driver.pendingFrames()
        val pendingUs = if (rate > 0 && pendingFrames > 0) pendingFrames * 1_000_000L / rate else 0L
        framesWritten = 0L
        // 漂移检测的预期 PTS = 锚点 + 积压 + 已写帧。积压在重锚后才开始被消费，
        // 按定义应计入"待播内容"，与已写帧同性质。
        backlogFramesAtAnchor = pendingFrames
        playedFramesBase = driver.playedFrames()
        startTimeUs = presentationTimeUs - pendingUs
    }

    override fun handleDiscontinuity() {
        super.handleDiscontinuity()
        // 切歌（onProcessedStreamChange）通知。对齐 DefaultAudioSink：只标记"下个
        // buffer 需要重锚定"，绝不 flushRing——旧曲 EOS 触发时刻 ring 里仍可能有
        // ~1.5s + 120ms 在途 ≈ 2 秒旧曲尾部没播完，flush 会整段丢弃（听感"歌尾
        // 被切"）。这里不需要动任何状态：若新曲 PTS 与锚点+已写帧偏差 > 200ms，
        // handleBuffer 的偏差检测会用补偿式 anchorTo 重锚（见上）；若 PTS 连续
        // （ExoPlayer 跨 period 的 stream offset 机制），什么都不做才是正确的。
        Log.i(TAG, "sink handleDiscontinuity（上游宣布流变了；本身不清 ring）")
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
            // 重锚 epoch 同步：漂移检测的 expected = 锚点 PTS + 锚点时积压 + 此后已写帧。
            // play() 把锚点改写成冻结的"位置"值（换了坐标系），必须把已写帧清零、积压
            // 刷成 ring 当前实况，恢复后首个 buffer 的 expected 才贴合流时间线；否则会
            // 偏差"暂停前已播放时长"（可远超 200ms），每次恢复都白重锚一次。
            framesWritten = 0L
            backlogFramesAtAnchor = driver.pendingFrames()
            pausedPositionUs = C.TIME_UNSET
            // 看门狗在暂停期间没被喂（见 handleBuffer 的 pausedByUser 判断），
            // lastAdvanceNs 是暂停前的旧值；不重置会在恢复后立即被判定为"卡死太久"。
            watchdog.reset(SystemClock.elapsedRealtimeNanos())
        }
    }

    override fun flush() {
        super.flush()
        if (bypassActive) {
            // 播放中途的 flush = ExoPlayer 强行清空 ring（seek/重定向/出错恢复），
            // 属预期路径（seek 必经此路），记 info 留排查线索。
            Log.i(TAG, "sink flush（bypass 激活中）")
            driver.flushRing()
            framesWritten = 0L
            // ring 已清空：重锚 epoch 同步清零，expected 公式退化为"锚点 + 已写帧"。
            backlogFramesAtAnchor = 0L
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
            bypassActive = true
            lastEngageFailHash = 0
            framesWritten = 0L
            playedFramesBase = driver.playedFrames()
            startTimeUs = C.TIME_UNSET
            eosRequested = false
            watchdog.reset(SystemClock.elapsedRealtimeNanos())
            // USB 独占时 delegate 的 AudioTrack 永不初始化（handleBuffer 全走泵、
            // 从不写 delegate），Media3 的 onAudioTrackInitialized 回调因此不触发，
            // UI 会一直显示"未播放"。这里把采样率写进诊断单例补上。
            //
            // 只补采样率，**不碰位深**：这条路径上的位深是 USB 线宽（本项目
            // PREFERRED_FLOAT_OUTPUT_BITS=32，设备 alt 也是 4 字节子帧），是"容器
            // 多粗"，不是"内容多少"。UI 要展示的是源的真实位深，由 PlaybackService
            // 按解码器实际输出（onAudioInputFormatChanged）写入——真机日志里
            // 44.1k 曲子 decoder output pcmEncoding=2（16bit），若在这里按线宽写
            // 就成了「32-bit」，误导用户以为源是 32bit。
            PlaybackDiagnostics.info.update { it.copy(sampleRateHz = sampleRate) }
        } else {
            bypassActive = false
            lastEngageFailHash = formatHash
            Log.w(TAG, "USB 等时泵启动失败，回退系统音频输出: error=$result")
        }
    }

    private fun checkWatchdog() {
        if (!bypassActive) return
        val now = SystemClock.elapsedRealtimeNanos()
        // 心跳 / 退出标志让看门狗区分「泵卡死」与「CPU 饥饿」：前台重负载把事件线程
        // 饿住时 playedFrames 也不涨，旧逻辑会误判成泵死并 flushRing + 重启流，
        // 丢弃 ring 里约 1 秒未播音频 = 听感断音/跳秒（见 IsoPumpWatchdog 文档）。
        if (watchdog.check(now, driver.playedFrames(), driver.heartbeat(), driver.loopExited())) {
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
        // 暖机：start_stream 后要经历 120ms 在途静音预卷 + 300ms ring 预缓冲，
        // 真实数据到设备前 playedFrames 不推进，400ms 太紧会误判；800ms 留余量。
        private const val ISO_WARMUP_NS = 800_000_000L

        // 卡死判定阈值：真机日志证实前台重负载会把事件线程（即使 nice -16）
        // 饿出 0.4~1s 的调度空隙。400ms 会把"暂时饿着"误判成"泵死了"，触发
        // flushRing 丢弃 ring 里 ~1.5s 未播音频 + 重启流 = 听感"往后跳两秒"。
        // 放宽到 1500ms：短暂饥饿自愈（在途队列 ~120ms 撑住设备端，恢复后续播
        // ring 数据，不丢内容不跳秒）；泵真死也只晚 1.1s 回退，代价可接受。
        private const val ISO_STALL_NS = 1_500_000_000L
        private const val PREFERRED_FLOAT_OUTPUT_BITS = 32
        private val FALLBACK_FLOAT_OUTPUT_BITS = intArrayOf(24, 16)

        /** PTS 与锚点+已写帧的偏差超过该值即重锚定（与 DefaultAudioSink 阈值一致）。 */
        private const val POSITION_RESYNC_THRESHOLD_US = 200_000L
    }
}
