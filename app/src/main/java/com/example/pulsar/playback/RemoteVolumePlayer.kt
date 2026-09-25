package com.example.pulsar.playback

import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * 远程音量的落点：USB 独占有自己的音量通路（UAC2 硬件音量或 native 软件衰减），
 * 系统混音器音量对它无效。这里把音量读写接到那条通路上，具体由 PlaybackService
 * 实现（读服务内缓存的百分比、写回控制器与 DataStore）。
 */
interface DeviceVolumeSource {

    /** 当前音量百分比（0..100）。 */
    fun currentPercent(): Int

    /**
     * 音量变化回调，[percent] 已夹紧到 0..100。实现方负责落盘与下发给 USB 控制器；
     * 只有值真的变化时才会被调用（重复的边界按键不会触发）。
     */
    fun onVolumeChange(percent: Int)
}

/**
 * 把播放器包装成"远程音量设备"，让 Media3 自动向系统注册远程音量 provider：
 * 音量键与系统音量条都会落到这里，再转发给 [DeviceVolumeSource]（USB 独占音量通路）。
 *
 * 挂载方式：`mediaSession.setPlayer(RemoteVolumePlayer(exoPlayer, source))`；摘除就是
 * `mediaSession.setPlayer(exoPlayer)`。Media3 每次换 player 都会重读 [getDeviceInfo]
 * （`MediaSessionImpl.setPlayerInternal` → `MediaSessionLegacyStub.onPlayerChanged` →
 * `onDeviceInfoChanged`），据此在 `setPlaybackToRemote`/`setPlaybackToLocal` 之间切换，
 * 不需要显式发事件，也不碰系统内部 API。
 *
 * 为什么是"远程"而不是直接调 `VolumeProvider`：Media3 1.5.1 没有公开的
 * `setPlaybackToRemote` API，但 `PlayerWrapper.createVolumeProviderCompat()` 会读取
 * player 的 `DeviceInfo.playbackType`——非 LOCAL 就挂远程 provider，并按可用命令决定
 * 控制档位（有 SET 命令 → `VOLUME_CONTROL_ABSOLUTE`，系统音量条可拖）。因此
 * [SupportedDeviceVolumeCommands] 必须带 SET，否则音量条只能看不能拖。
 *
 * 基类选 [ForwardingPlayer]（公开稳定 API）而不是 `SimpleBasePlayer`（`@UnstableApi`，
 * 未覆写命令直接抛异常）。代价是发不出 `onDeviceVolumeChanged` 事件，系统音量面板
 * 显示的值依赖其 1 秒乐观猜测——只在"面板开着同时用 App 内滑块调音量"时短暂可见。
 *
 * 平台限制：AOSP 只把音量键转发给 PLAYING 状态的会话（`MediaSessionStack` 的
 * `getDefaultVolumeSession()` 要求 `checkPlaybackActiveState(true)`），所以暂停时
 * 音量键仍然调系统音量、对 USB 输出无效。
 */
@UnstableApi
class RemoteVolumePlayer(
    delegate: Player,
    private val source: DeviceVolumeSource?,
) : ForwardingPlayer(delegate) {

    companion object {
        /** 音量键一次按键的步进（百分点）。5% 对应多数 DAC 上约 3 dB。 */
        const val STEP_PERCENT = 5

        /** 音量键盘与系统音量条都走这两组命令，缺 SET 会让音量条拖不动。 */
        private val SupportedDeviceVolumeCommands =
            Player.Commands.Builder()
                .add(Player.COMMAND_GET_DEVICE_VOLUME)
                .add(Player.COMMAND_SET_DEVICE_VOLUME)
                .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
                .add(Player.COMMAND_ADJUST_DEVICE_VOLUME)
                .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
                .build()

        /** 覆写 [getAvailableCommands] 时并入被包装播放器的命令，不吞掉其它能力。 */
        private fun withDeviceVolumeCommands(base: Player.Commands): Player.Commands {
            val builder = Player.Commands.Builder()
            for (index in 0 until base.size()) {
                builder.add(base.get(index))
            }
            return builder.addAll(SupportedDeviceVolumeCommands).build()
        }
    }

    private var volume = (source?.currentPercent() ?: 100).coerceIn(0, 100)

    /** 声明成远程设备：Media3 据此挂 [androidx.media3.session.MediaSession] 的远程音量 provider。 */
    override fun getDeviceInfo(): DeviceInfo =
        DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(100)
            .build()

    override fun getDeviceVolume(): Int = volume

    override fun isDeviceMuted(): Boolean = volume == 0

    /** 音量条拖动（绝对值）。 */
    override fun setDeviceVolume(volume: Int, flags: @androidx.media3.common.C.VolumeFlags Int) {
        update(volume)
    }

    // 步进以 [source] 的当前值为基准：它才是 USB 音量的唯一真源（delegate 是播放器，
    // 在独占路径下不掌握真实音量）。写回去也通过 source，不额外调 delegate 的音量方法
    // ——真源只有一处，避免"两边各算一次"。
    override fun increaseDeviceVolume(flags: @androidx.media3.common.C.VolumeFlags Int) {
        update((source?.currentPercent() ?: volume) + STEP_PERCENT)
    }

    override fun decreaseDeviceVolume(flags: @androidx.media3.common.C.VolumeFlags Int) {
        update((source?.currentPercent() ?: volume) - STEP_PERCENT)
    }

    // 已废弃的无参重载：显式接到上面带 flags 的实现。ForwardingPlayer 默认会把它们
    // 转发给 delegate（实测会绕过本类的音量转发），必须拦下来走同一条路。
    @Deprecated("Use setDeviceVolume(int, int) instead.")
    override fun setDeviceVolume(volume: Int) = setDeviceVolume(volume, 0)

    @Deprecated("Use increaseDeviceVolume(int) instead.")
    override fun increaseDeviceVolume() = increaseDeviceVolume(0)

    @Deprecated("Use decreaseDeviceVolume(int) instead.")
    override fun decreaseDeviceVolume() = decreaseDeviceVolume(0)

    @Deprecated("Use setDeviceMuted(boolean, int) instead.")
    override fun setDeviceMuted(muted: Boolean) = setDeviceMuted(muted, 0)

    /**
     * 平台协议：MUTE 就是"音量归 0"，0 即静音；UNMUTE 只表示不再强制静音，音量值本身
     * 停在 0（用户想恢复听感要自己按音量键），不恢复静音前的值。
     */
    override fun setDeviceMuted(muted: Boolean, flags: @androidx.media3.common.C.VolumeFlags Int) {
        if (muted) update(0)
    }

    /**
     * 必须覆写：ExoPlayer 的命令集随播放状态变化（空闲/暂停时可能少一批），而
     * `ForwardingPlayer` 默认透传它的结果，会让 Media3 误判成 `VOLUME_CONTROL_FIXED`
     * 或 `RELATIVE`——表现为音量条拖不动。这里把设备音量命令钉死常驻。
     */
    override fun getAvailableCommands(): Player.Commands =
        withDeviceVolumeCommands(super.getAvailableCommands())

    /**
     * 从 [source] 拉取最新值刷新展示（**不回调** source，避免回路）。
     *
     * 系统音量面板显示的数字取自注册时读过一次的 VolumeProvider 当前值，App 内滑块
     * 调音量不经过这里，面板会一直停在旧数字，直到用户按一次音量键才被平台的"乐观
     * 猜测"短暂纠正。所以在 [RemoteVolumePlayer] 挂在 session 上、而音量从别的路径
     * （App 内滑块、DataStore 变更）改掉之后，调一次本方法把面板对齐。
     */
    fun syncFromSource() {
        val current = source?.currentPercent() ?: return
        volume = current.coerceIn(0, 100)
    }

    private fun update(next: Int) {
        val clamped = next.coerceIn(0, 100)
        if (clamped == volume) return
        volume = clamped
        source?.onVolumeChange(clamped)
    }
}
