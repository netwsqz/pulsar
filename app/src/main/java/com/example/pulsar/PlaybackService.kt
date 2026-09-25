package com.example.pulsar

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import com.example.pulsar.playback.DeviceVolumeSource
import com.example.pulsar.playback.LoudnessGainAudioSink
import com.example.pulsar.playback.LoudnessGainProvider
import com.example.pulsar.playback.PlaybackDiagnostics
import com.example.pulsar.playback.PlayerRebuildState
import com.example.pulsar.playback.RemoteVolumePlayer
import com.example.pulsar.playback.SilenceLevelMonitor
import com.example.pulsar.playback.SilenceSkipConfig
import com.example.pulsar.playback.SilenceSkipDetector
import com.example.pulsar.playback.SilenceSkipMediaCommands
import com.example.pulsar.playback.SilenceSkipState
import com.example.pulsar.playback.SilenceSkippingAudioSink
import com.example.pulsar.playback.SleepTimer
import com.example.pulsar.playback.UsbAudioOutput
import com.example.pulsar.playback.decoderEncodingForDiagnostics
import com.example.pulsar.playback.normalizePlayerPosition
import com.example.pulsar.playback.UsbExclusiveMediaCommands
import com.example.pulsar.playback.UNITY_GAIN
import com.example.pulsar.playback.loudnessGainDb
import com.example.pulsar.playback.loudnessGainLinear
import com.example.pulsar.playback.usb.LibusbAudioSink
import com.example.pulsar.playback.usb.UsbExclusiveController
import com.example.pulsar.playback.usb.UsbExclusiveState
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PlaybackService : MediaSessionService() {
    private companion object {
        const val TAG = "PlaybackService"
        const val kDefaultVolumePercent = 80
        // 静音跳曲参数：默认值，暂未支持用户可调（后续真机试听调优可能需要放开）。
        const val kSilenceThresholdDb = -50.0
        const val kSilenceRequiredDurationMs = 800L
        const val kSilenceTailWindowMs = 15_000L
        const val kSilencePollIntervalMs = 200L
        // USB 独占输出声明给 ExoPlayer 的最大采样率。默认 AudioCapabilities 来自系统
        // AudioTrack 能力报告，插上 USB DAC 后常被压成 44.1k，导致 192k 源被 ExoPlayer
        // 重采样后才交给 USB 泵。声明到 192k，让 ExoPlayer 不降采样，由 USB 泵协商真实采样率。
        const val kMaxUsbSampleRateHz = 192_000
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val usbExclusiveController by lazy { UsbExclusiveController(this) }
    private val usbExclusivePreferences by lazy { UsbExclusivePreferences(pulsarPreferencesDataStore) }
    private var usbExclusiveEnabled = false
    private var usbExclusiveState: UsbExclusiveState = UsbExclusiveState.Disabled
    private var usbVolumePercent = kDefaultVolumePercent
    private var rebuildingPlayer = false

    // 静音跳曲：音频 sink（播放线程）写电平，轮询协程读电平结合位置/时长判定。
    // 曾尝试后台预分析提前算出精确边界做到真无缝，但真机验证发现 Jellyfin 流式
    // 接口疑似不支持 seek 到指定字节区间（seek 记账的位置正确，实际解出的音频内容
    // 却错位，两首不同的歌测出几乎相同的尾部电平），已放弃该方向，只保留这套已
    // 验证工作正常的实时电平兜底检测（详见本次改动对应的排查过程）。
    private val silenceLevelMonitor = SilenceLevelMonitor()
    private var silenceSkipState = SilenceSkipState()
    private val silenceSkipConfig = SilenceSkipConfig(
        thresholdDb = kSilenceThresholdDb,
        requiredDurationMs = kSilenceRequiredDurationMs,
        tailWindowMs = kSilenceTailWindowMs
    )
    private val silenceSkipPreferences by lazy { SilenceSkipPreferences(pulsarPreferencesDataStore) }
    // 静音跳曲开关：默认开启，与 SilenceSkipPreferences 默认值一致。
    private var silenceSkipEnabled = true

    // 回放增益（ReplayGain）：换歌时把当前曲目的线性增益写进 provider，音频线程每帧读。
    private val loudnessGainProvider = LoudnessGainProvider()
    private val loudnessPreferences by lazy { LoudnessPreferences(pulsarPreferencesDataStore) }
    private var loudnessGainEnabled = LoudnessPreferences.DEFAULT_ENABLED

    // 播放服务自身的协程作用域：监听 USB 音频设备插拔与采样率采集。
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val audioManager: AudioManager
        get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 当前检测到的 USB 音频输出设备（无则为 null）。 */
    private val usbOutputDevice = MutableStateFlow<AudioDeviceInfo?>(null)

    /** 上次写入 [usbOutputDevice] 的 (type, id) 对，用于去重。 */
    private var lastUsbDeviceId: Pair<Int, Int>? = null

    private val usbSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(UsbExclusiveMediaCommands.SET_ENABLED, Bundle.EMPTY))
                .add(SessionCommand(UsbExclusiveMediaCommands.GET_STATE, Bundle.EMPTY))
                .add(SessionCommand(UsbExclusiveMediaCommands.SET_VOLUME, Bundle.EMPTY))
                .add(SessionCommand(UsbExclusiveMediaCommands.ADJUST_VOLUME, Bundle.EMPTY))
                .add(SessionCommand(SilenceSkipMediaCommands.SET_ENABLED, Bundle.EMPTY))
                .add(SessionCommand(SilenceSkipMediaCommands.GET_STATE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): com.google.common.util.concurrent.ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                UsbExclusiveMediaCommands.SET_ENABLED -> {
                    val future = SettableFuture.create<SessionResult>()
                    if (!args.containsKey(UsbExclusiveMediaCommands.KEY_ENABLED)) {
                        future.set(UsbExclusiveMediaCommands.errorResult("缺少 enabled 参数"))
                    } else {
                        serviceScope.launch {
                            runCatching {
                                usbExclusivePreferences.setEnabled(
                                    args.getBoolean(UsbExclusiveMediaCommands.KEY_ENABLED)
                                )
                            }.onSuccess { future.set(SessionResult(SessionResult.RESULT_SUCCESS)) }
                                .onFailure { error ->
                                    if (error is CancellationException) throw error
                                    future.set(UsbExclusiveMediaCommands.errorResult("保存 USB 独占设置失败"))
                                }
                        }
                    }
                    future
                }
                UsbExclusiveMediaCommands.GET_STATE ->
                    com.google.common.util.concurrent.Futures.immediateFuture(
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            UsbExclusiveMediaCommands.stateBundle(
                                usbExclusiveEnabled, usbExclusiveState,
                                usbVolumePercent,
                            )
                        )
                    )
                UsbExclusiveMediaCommands.SET_VOLUME -> {
                    if (!args.containsKey(UsbExclusiveMediaCommands.KEY_VOLUME_PERCENT)) {
                        com.google.common.util.concurrent.Futures.immediateFuture(
                            UsbExclusiveMediaCommands.errorResult("缺少 volumePercent 参数")
                        )
                    } else {
                        val percent = args.getInt(
                            UsbExclusiveMediaCommands.KEY_VOLUME_PERCENT, kDefaultVolumePercent
                        )
                        applyUsbVolume(percent)
                        com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        )
                    }
                }
                UsbExclusiveMediaCommands.ADJUST_VOLUME -> {
                    if (!args.containsKey(UsbExclusiveMediaCommands.KEY_VOLUME_STEP)) {
                        com.google.common.util.concurrent.Futures.immediateFuture(
                            UsbExclusiveMediaCommands.errorResult("缺少 volumeStep 参数")
                        )
                    } else {
                        // 相对音量按服务里的实时值算：前台音量键那条路只有 500ms 轮询来的
                        // 快照，自己算会把连按的第二下算成同一个目标值而丢档。
                        val step = args.getInt(UsbExclusiveMediaCommands.KEY_VOLUME_STEP)
                        applyUsbVolume(usbVolumePercent + step * RemoteVolumePlayer.STEP_PERCENT)
                        // 算完的新音量随结果带回（applyUsbVolume 已夹紧并写回 usbVolumePercent）：
                        // 界面要拿它立刻弹音量浮窗，不能等下一次 500ms 轮询。
                        com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(
                                SessionResult.RESULT_SUCCESS,
                                UsbExclusiveMediaCommands.volumeResultArgs(usbVolumePercent)
                            )
                        )
                    }
                }
                SilenceSkipMediaCommands.SET_ENABLED -> {
                    val future = SettableFuture.create<SessionResult>()
                    if (!args.containsKey(SilenceSkipMediaCommands.KEY_ENABLED)) {
                        future.set(SilenceSkipMediaCommands.errorResult("缺少 enabled 参数"))
                    } else {
                        val enabled = args.getBoolean(SilenceSkipMediaCommands.KEY_ENABLED)
                        silenceSkipEnabled = enabled
                        silenceSkipState = SilenceSkipState()
                        serviceScope.launch {
                            runCatching { silenceSkipPreferences.setEnabled(enabled) }
                                .onSuccess { future.set(SessionResult(SessionResult.RESULT_SUCCESS)) }
                                .onFailure { error ->
                                    if (error is CancellationException) throw error
                                    future.set(SilenceSkipMediaCommands.errorResult("保存静音跳曲设置失败"))
                                }
                        }
                    }
                    future
                }
                SilenceSkipMediaCommands.GET_STATE ->
                    com.google.common.util.concurrent.Futures.immediateFuture(
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            SilenceSkipMediaCommands.stateBundle(silenceSkipEnabled)
                        )
                    )
                else -> com.google.common.util.concurrent.Futures.immediateFuture(
                    UsbExclusiveMediaCommands.errorResult("未知命令")
                )
            }
        }
    }
    private val usbAudioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshUsbOutputDevice()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshUsbOutputDevice()
    }

    override fun onCreate() {
        super.onCreate()
        // usbExclusiveEnabled 决定用哪条音频链路、以及要不要把播放器包装成远程音量设备。
        // 这两件事都在 onCreate 里同步发生，而异步 collector 还没跑，所以必须先同步读一次
        // 持久化值——否则冷启动时它会停在初始值 false，独占路径和音量键接管都不会挂上。
        // 音量初值一并同步读，保持两处初始化时序一致。
        runBlocking {
            usbExclusiveEnabled = usbExclusivePreferences.enabled.first()
            usbVolumePercent = usbExclusivePreferences.volumePercent.first()
        }
        // 与 usbExclusiveEnabled 同步启用/停用独占控制器：这一步以前是搭在"首帧 collector
        // 触发 rebuildPlayer"上的，现在初始值已就位、不会再触发重建，必须在这里显式做，
        // 否则冷启动恢复独占时 USB 泵不会被打开。
        if (usbExclusiveEnabled) usbExclusiveController.enable() else usbExclusiveController.disable()
        val exoPlayer = createPlayer(usbExclusiveEnabled)
        player = exoPlayer

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, playerForSession(exoPlayer))
            .setSessionActivity(sessionActivity)
            .setCallback(usbSessionCallback)
            .build()

        observeUsbAudioOutput()
        serviceScope.launch {
            usbExclusivePreferences.volumePercent.collect { percent ->
                applyUsbVolume(percent)
            }
        }
        serviceScope.launch {
            usbExclusivePreferences.enabled.collect { enabled ->
                if (enabled != usbExclusiveEnabled) rebuildPlayer(enabled)
            }
        }
        serviceScope.launch {
            usbExclusiveController.state.collect { usbExclusiveState = it }
        }
        serviceScope.launch {
            silenceSkipPreferences.enabled.collect { enabled ->
                silenceSkipEnabled = enabled
                silenceSkipState = SilenceSkipState()
            }
        }
        serviceScope.launch {
            loudnessPreferences.enabled.collect { enabled ->
                loudnessGainEnabled = enabled
                updateLoudnessGain()
            }
        }
        // 定时关闭到点动作：暂停播放。定时器本身在进程级单例 SleepTimer 里，服务销毁也不影响倒计时。
        SleepTimer.onTimeout = { player?.pause() }
        observeSilenceSkip()
    }

    /**
     * 静音跳曲轮询：定期读取当前播放位置/时长与音频 sink 写入的实时电平，交给
     * [SilenceSkipDetector] 判定。只在曲目末尾窗口内、且连续静音超过阈值时才
     * 触发 [ExoPlayer.seekToNext]（硬切，不做淡出）。
     *
     * 触发后立即重置检测状态：seekToNext 后位置/时长会变成下一曲的，旧状态延续
     * 到下一曲会造成误判（尤其是下一曲一开始就很安静的情况）。
     */
    private fun observeSilenceSkip() {
        serviceScope.launch {
            while (isActive) {
                delay(kSilencePollIntervalMs)
                if (!silenceSkipEnabled) continue
                val exoPlayer = player ?: continue
                if (!exoPlayer.playWhenReady || exoPlayer.mediaItemCount == 0) continue
                val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET }
                val result = SilenceSkipDetector.evaluate(
                    state = silenceSkipState,
                    levelDb = silenceLevelMonitor.levelDb,
                    positionMs = exoPlayer.currentPosition,
                    durationMs = duration,
                    config = silenceSkipConfig
                )
                silenceSkipState = result.state
                if (result.shouldSkip) {
                    silenceSkipState = SilenceSkipState()
                    exoPlayer.seekToNext()
                }
            }
        }
    }

    /**
     * 音量键/系统音量条的落点。USB 独占下系统混音器音量对输出无效，音量真源是
     * [UsbExclusivePreferences.volumePercent]，本服务内缓存在 [usbVolumePercent]。
     */
    private val remoteVolumeSource = object : DeviceVolumeSource {
        override fun currentPercent(): Int = usbVolumePercent

        override fun onVolumeChange(percent: Int) {
            applyUsbVolume(percent)
        }
    }

    /**
     * 统一音量入口：SET_VOLUME 命令（App 内滑块）、DataStore 变更、远程音量回调
     * 三条路都走这里，保证 [usbVolumePercent] 与控制器、持久化三者一致。
     */
    private fun applyUsbVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        usbVolumePercent = clamped
        usbExclusiveController.setVolume(clamped)
        // 系统音量面板的数字取自注册时读过一次的 provider 当前值；音量从 App 内滑块等
        // 非音量键路径变化后，这里同步一次把面板对齐，否则面板会一直停在旧数字。
        (mediaSession?.player as? RemoteVolumePlayer)?.syncFromSource()
        serviceScope.launch {
            runCatching { usbExclusivePreferences.setVolumePercent(clamped) }
                .onFailure { error ->
                    if (error !is CancellationException) Log.w(TAG, "保存 USB 音量失败", error)
                }
        }
    }

    /**
     * 交给 MediaSession 的播放器：USB 独占时包一层 [RemoteVolumePlayer]，让系统把
     * 音量键与系统音量条交给 App（详见该类注释）；退出独占时交回裸播放器，音量键
     * 立刻恢复系统行为。挂/摘就是这一次选择——Media3 每次换 player 都会重读
     * DeviceInfo 并重挂远程音量 provider。
     */
    private fun playerForSession(exoPlayer: ExoPlayer): Player =
        if (usbExclusiveEnabled) RemoteVolumePlayer(exoPlayer, remoteVolumeSource) else exoPlayer

    /**
     * 回放增益（ReplayGain）：把当前曲目的响度增益换算成线性系数写进
     * [loudnessGainProvider]。换歌、切换开关、重建播放器后都要重算一次。
     */
    private fun updateLoudnessGain() {
        updateLoudnessGain(player)
    }

    private fun updateLoudnessGain(exoPlayer: ExoPlayer?) {
        val gainDb = exoPlayer?.currentMediaItem?.mediaMetadata?.extras?.loudnessGainDb()
        val linear = if (loudnessGainEnabled) loudnessGainLinear(gainDb) else UNITY_GAIN
        loudnessGainProvider.linearGain = linear
        // 诊断：界面上看不出增益是否生效，留一条日志供真机排查（媒体信息没传到服务端时
        // 会一直打 gainDb=null，一眼能认出来）。
        Log.i(TAG, "loudness gain: gainDb=$gainDb enabled=$loudnessGainEnabled linear=$linear")
    }

    private fun observeLoudnessGain(exoPlayer: ExoPlayer) {
        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateLoudnessGain(exoPlayer)
            }
        })
    }

    private fun createPlayer(isUsbExclusive: Boolean): ExoPlayer {
        // 静音跳曲检测只读电平不改数据，包在两条链路的最外层都安全（USB 独占路径的
        // 字节对齐逻辑在 LibusbAudioSink 内部完成，不受外层包裹影响）。
        val renderersFactory = if (isUsbExclusive) {
            object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink {
                    // 声明 USB 泵实际支持的能力：16/24bit（与协商后的 USB 格式位深匹配，
                    // 见 UsbExclusiveController.negotiateFormat，位深不一致会帧宽错位破音），
                    // 采样率放开到 192k，否则 192k 源会被系统 AudioTrack 能力报告压成 44.1k。
                    val delegate = DefaultAudioSink.Builder(context)
                        .setAudioCapabilities(
                            AudioCapabilities(
                                intArrayOf(
                                    C.ENCODING_PCM_16BIT,
                                    C.ENCODING_PCM_24BIT,
                                    C.ENCODING_PCM_FLOAT,
                                ),
                                kMaxUsbSampleRateHz,
                            )
                        )
                        .setEnableFloatOutput(true)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build()
                    return SilenceSkippingAudioSink(
                        // 回放增益夹在电平表与泵之间：电平表读到的是增益前的电平，
                        // 静音跳曲的阈值判定不受影响。
                        LoudnessGainAudioSink(
                            LibusbAudioSink(delegate, usbExclusiveController),
                            loudnessGainProvider
                        ),
                        silenceLevelMonitor
                    )
                }
            }.setEnableAudioFloatOutput(true)
        } else {
            object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink {
                    val delegate = DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build()
                    return SilenceSkippingAudioSink(
                        LoudnessGainAudioSink(delegate, loudnessGainProvider),
                        silenceLevelMonitor
                    )
                }
            }.setEnableAudioFloatOutput(true)
        }

        return ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            // 播放期间持有 PARTIAL_WAKE_LOCK。普通模式系统 AudioTrack 自带
            // wakelock，但 USB 独占模式完全绕过 AudioTrack（delegate 永不初始化），
            // 不加这句熄屏后 CPU 休眠 → libusb 事件线程停摆 → 在途 ~64ms 缓冲
            // 耗尽补零（爆音），超 400ms 看门狗回退系统音频（中断）。
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also {
                observeOutputDiagnostics(it)
                observeLoudnessGain(it)
            }
    }

    /**
     * 播放数据源：手机本地副本（缓存）套在原始网络源外面——命中读本地、未命中边读边写，
     * LRU 淘汰由 `SimpleCache` 自己管，不自己造轮子。
     *
     * [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR] 是 spec 铁律 1 的落点：读缓存出错就
     * 退回网络。缓存层的问题绝不能变成播不了歌。
     *
     * 缓存实例在 [PulsarApplication] 里（进程级单例，界面也在用它查完整副本），
     * 这里只取用不释放——释放会让界面的查询全变成异常。
     */
    private fun cacheDataSourceFactory(): DataSource.Factory = CacheDataSource.Factory()
        .setCache((application as PulsarApplication).mediaCacheStore)
        .setUpstreamDataSourceFactory(
            AuthHttpDataSourceFactory { (application as PulsarApplication).activeAuthorization }
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun capturePlayerState(oldPlayer: ExoPlayer): PlayerRebuildState {
        val items = (0 until oldPlayer.mediaItemCount)
            .map(oldPlayer::getMediaItemAt)
        val (index, position) = normalizePlayerPosition(
            items.size,
            oldPlayer.currentMediaItemIndex,
            oldPlayer.currentPosition,
        )
        return PlayerRebuildState(
            mediaItems = items,
            mediaItemIndex = index,
            positionMs = position,
            playWhenReady = oldPlayer.playWhenReady,
            repeatMode = oldPlayer.repeatMode,
            shuffleModeEnabled = oldPlayer.shuffleModeEnabled,
            playbackParameters = oldPlayer.playbackParameters,
        )
    }

    private fun restorePlayerState(newPlayer: ExoPlayer, state: PlayerRebuildState) {
        if (state.mediaItems.isNotEmpty() && state.hasCurrentItem) {
            newPlayer.setMediaItems(state.mediaItems, state.mediaItemIndex, state.positionMs)
        } else {
            newPlayer.clearMediaItems()
        }
        newPlayer.setRepeatMode(state.repeatMode)
        newPlayer.setShuffleModeEnabled(state.shuffleModeEnabled)
        newPlayer.setPlaybackParameters(state.playbackParameters)
        if (state.hasCurrentItem) newPlayer.prepare()
    }

    private fun rebuildPlayer(isUsbExclusive: Boolean) {
        if (rebuildingPlayer || isUsbExclusive == usbExclusiveEnabled) return
        val oldPlayer = player ?: run {
            usbExclusiveEnabled = isUsbExclusive
            if (isUsbExclusive) usbExclusiveController.enable()
            else usbExclusiveController.disable()
            return
        }
        rebuildingPlayer = true
        val state = capturePlayerState(oldPlayer)
        val newPlayer = runCatching { createPlayer(isUsbExclusive) }.getOrElse { error ->
            rebuildingPlayer = false
            Log.e(TAG, "创建 USB 输出模式播放器失败，保留旧播放器", error)
            return
        }

        try {
            oldPlayer.playWhenReady = false
            restorePlayerState(newPlayer, state)
            newPlayer.setPreferredAudioDevice(usbOutputDevice.value)
            mediaSession?.setPlayer(playerForSession(newPlayer))
            if (isUsbExclusive) usbExclusiveController.enable()
            else usbExclusiveController.disable()
            player = newPlayer
            usbExclusiveEnabled = isUsbExclusive
            // 新播放器的 currentMediaItem 已经就位，按它重算一次增益（换播放器时
            // 队列是整份搬过去的，旧播放器的监听器不会再触发）。
            updateLoudnessGain()
            oldPlayer.release()
            newPlayer.playWhenReady = state.playWhenReady
        } catch (error: Throwable) {
            runCatching { mediaSession?.setPlayer(oldPlayer) }
            runCatching {
                if (usbExclusiveEnabled) usbExclusiveController.enable()
                else usbExclusiveController.disable()
            }
            runCatching { oldPlayer.playWhenReady = state.playWhenReady }
            newPlayer.release()
            rebuildingPlayer = false
            Log.e(TAG, "切换 USB 输出模式失败，保留旧播放器", error)
            return
        }
        PlaybackDiagnostics.info.update { it.copy(sampleRateHz = null, encoding = null) }
        rebuildingPlayer = false
    }

    /**
     * USB 音频输出（常开）：插着 USB 音频设备时，把播放器路由到 USB 输出，
     * 保证声音从小尾巴/DAC 出来而不是从扬声器或蓝牙；拔掉自动回退系统默认。
     * 系统默认路由通常已自动走 USB，这里的显式路由保证任何设备上行为一致。
     */
    private fun observeUsbAudioOutput() {
        refreshUsbOutputDevice()
        audioManager.registerAudioDeviceCallback(usbAudioCallback, null)
        serviceScope.launch {
            usbOutputDevice.collect { device ->
                player?.setPreferredAudioDevice(device)
                if (device != null) probeBitPerfectSupport(device)
                PlaybackDiagnostics.info.update { it.copy(viaUsb = device != null) }
            }
        }
    }

    /**
     * 采样率与编码采集：AudioTrack 打开/重新配置时记录实际参数；播放器进入
     * IDLE 时清空，避免停止后还显示上一首歌的参数。
     */
    private fun observeOutputDiagnostics(exoPlayer: ExoPlayer) {
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                // 诊断：解码器实际输出的格式（pcmEncoding: 16bit=2, float=4, 24bit=21）。
                // 确认 24bit 源是被解码器降位深，还是被 ExoPlayer 管线降位深，决定修复位置。
                Log.i(
                    TAG,
                    "decoder output: rate=${format.sampleRate} ch=${format.channelCount} " +
                        "pcmEncoding=${format.pcmEncoding}",
                )
                // UI 的位深展示取这里的值——它是**源的真实位深**。不用 AudioTrack 的
                // 编码（系统默认输出会被统一成 float32），也不该用 USB 线宽（独占路径
                // 按 32bit 子帧送，那是容器宽度）。压缩格式没有 pcmEncoding，挡掉。
                decoderEncodingForDiagnostics(format.pcmEncoding)?.let { encoding ->
                    PlaybackDiagnostics.info.update { it.copy(encoding = encoding) }
                }
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig
            ) {
                val sampleRate = audioTrackConfig.sampleRate.takeIf { it > 0 } ?: return
                // 采样率取 AudioTrack 的实际值；位深不取它——见 onAudioInputFormatChanged。
                PlaybackDiagnostics.info.update { it.copy(sampleRateHz = sampleRate) }
            }

            override fun onPlaybackStateChanged(
                eventTime: AnalyticsListener.EventTime,
                playbackState: Int
            ) {
                if (playbackState == Player.STATE_IDLE) {
                    PlaybackDiagnostics.info.update {
                        it.copy(sampleRateHz = null, encoding = null)
                    }
                }
            }
        })
    }

    /**
     * 探测当前 USB 设备是否支持 Android 14+ 的 bit-perfect mixer（独占 + 不重采样）。
     * 仅探测并记录到日志：实际启用与全局 float 输出有冲突，等真机确认支持后再做模式切换。
     */
    private fun probeBitPerfectSupport(usbDevice: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT < 34) return
        serviceScope.launch(Dispatchers.Default) {
            runCatching {
                val mediaAttrs = AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val supported = audioManager.getSupportedMixerAttributes(usbDevice)
                val hasBitPerfect = supported.any { attr ->
                    attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                }
                Log.i(TAG, "USB bit-perfect 独占支持: bitPerfect=$hasBitPerfect, " +
                    "支持项=${supported.map { it.mixerBehavior }}")
            }.onFailure { Log.w(TAG, "探测 USB bit-perfect 支持失败", it) }
        }
    }

    /**
     * 重新扫描当前输出设备，更新 [usbOutputDevice]。
     * 按 (type, id) 去重：不同设备插拔不触发 StateFlow 更新，避免重复路由调用。
     */
    private fun refreshUsbOutputDevice() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val usbType = UsbAudioOutput.findUsbOutputDeviceType(devices.map { it.type })
        val device = usbType?.let { type -> devices.firstOrNull { it.type == type } }
        val deviceId = device?.let { it.type to it.id }
        if (lastUsbDeviceId == deviceId) return
        lastUsbDeviceId = deviceId
        usbOutputDevice.value = device
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession.takeIf { controllerInfo.uid == applicationInfo.uid }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        player?.let {
            if (!it.playWhenReady || it.mediaItemCount == 0) {
                stopSelf()
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        // 清空定时回调，避免服务销毁后仍持有对播放器的引用；定时器倒计时照常（到点无播放器可暂停）。
        SleepTimer.onTimeout = null
        runCatching { audioManager.unregisterAudioDeviceCallback(usbAudioCallback) }
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        usbExclusiveController.release()
        super.onDestroy()
    }
}