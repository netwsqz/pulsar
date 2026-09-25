package com.example.pulsar.playback

import android.media.AudioManager
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.text.CueGroup
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RemoteVolumePlayer] 的行为规格：对 Media3 宣称"我是远程音量设备"，把平台下发的
 * 音量键/音量条回调转发给 [DeviceVolumeSource]。纯单元测试，不依赖 Android 运行时。
 *
 * Fake Player 只实现 [RemoteVolumePlayer] 真正依赖的设备音量面；Player 的其余方法
 * 一律抛 [NotImplementedError]，任何预期外的转发都会立刻把测试打红。
 */
@UnstableApi
class RemoteVolumePlayerTest {

    @Test
    fun `对外声明为远程音量设备且量程 0 到 100`() {
        val fake = FakePlayer(); val player = RemoteVolumePlayer(fake, fake)

        val info = player.deviceInfo

        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, info.playbackType)
        assertEquals(0, info.minVolume)
        assertEquals(100, info.maxVolume)
    }

    @Test
    fun `初值取自音量源`() {
        val fake = FakePlayer().apply { playerVolume = 37 }; val player = RemoteVolumePlayer(fake, fake)

        assertEquals(37, player.deviceVolume)
        assertFalse(player.isDeviceMuted)
    }

    @Test
    fun `加大音量每次 5 个百分点`() {
        val fake = FakePlayer()
        val player = RemoteVolumePlayer(fake, fake)

        player.increaseDeviceVolume(AudioManager.FLAG_SHOW_UI)

        assertEquals(55, player.deviceVolume)
        assertEquals(listOf(55), fake.changes)
    }

    @Test
    fun `减小音量每次 5 个百分点`() {
        val fake = FakePlayer()
        val player = RemoteVolumePlayer(fake, fake)

        player.decreaseDeviceVolume(AudioManager.FLAG_SHOW_UI)

        assertEquals(45, player.deviceVolume)
        assertEquals(listOf(45), fake.changes)
    }

    @Test
    fun `已在 100 时加大不越界也不回调`() {
        val fake = FakePlayer().apply { playerVolume = 100 }
        val player = RemoteVolumePlayer(fake, fake)

        player.increaseDeviceVolume(AudioManager.FLAG_SHOW_UI)

        assertEquals(100, player.deviceVolume)
        assertTrue(fake.changes.isEmpty())
    }

    @Test
    fun `已在 0 时减小不越界也不回调`() {
        val fake = FakePlayer().apply { playerVolume = 0 }
        val player = RemoteVolumePlayer(fake, fake)

        player.decreaseDeviceVolume(AudioManager.FLAG_SHOW_UI)

        assertEquals(0, player.deviceVolume)
        assertTrue(fake.changes.isEmpty())
    }

    @Test
    fun `接近边界时步进被夹到端点`() {
        val fake = FakePlayer().apply { playerVolume = 98 }
        val player = RemoteVolumePlayer(fake, fake)

        player.increaseDeviceVolume(AudioManager.FLAG_SHOW_UI)

        assertEquals(100, player.deviceVolume)
        assertEquals(listOf(100), fake.changes)
    }

    @Test
    fun `音量条拖动的绝对值被夹紧后原样落库`() {
        val fake = FakePlayer(); val player = RemoteVolumePlayer(fake, fake)

        player.setDeviceVolume(120, AudioManager.FLAG_SHOW_UI)

        assertEquals(100, player.deviceVolume)
        assertEquals(listOf(100), fake.changes)
    }

    @Test
    fun `静音归零且音量 0 视为静音`() {
        val fake = FakePlayer(); val player = RemoteVolumePlayer(fake, fake)

        player.setDeviceMuted(true, AudioManager.FLAG_SHOW_UI)

        assertEquals(0, player.deviceVolume)
        assertTrue(player.isDeviceMuted)
        assertEquals(listOf(0), fake.changes)
    }

    @Test
    fun `取消静音不恢复原值而停在 0`() {
        // 平台协议：MUTE 后 0 就是"静音"，UNMUTE 只表示不再强制静音，音量值本身
        // 已经是 0（用户想恢复听感需自己按音量键）。这条钉住当前语义，避免以后
        // 有人"好心"实现成恢复原值。
        val fake = FakePlayer(); val player = RemoteVolumePlayer(fake, fake)

        player.setDeviceMuted(true, AudioManager.FLAG_SHOW_UI)
        player.setDeviceMuted(false, AudioManager.FLAG_SHOW_UI)

        // 撤销静音后音量停在 0（= 仍处于静音态），不会"恢复到静音前的值"——
        // 想恢复听感得自己按音量键，这是与平台语义一致的取舍。
        assertEquals(0, player.deviceVolume)
        assertTrue(player.isDeviceMuted)
        assertEquals(listOf(0), fake.changes)
    }

    @Test
    fun `无人接收音量时静默丢弃不崩溃`() {
        // 音量源可空：本地模式（USB 独占关闭）下包装仍可能挂在 session 上，
        // 此时音量键不该崩，也不该有副作用。
        val fake = FakePlayer()
        val player = RemoteVolumePlayer(fake, null)

        player.increaseDeviceVolume(AudioManager.FLAG_SHOW_UI)

        assertEquals(100, player.deviceVolume)
    }

    @Test
    fun `从音量源同步展示但不产生回调回环`() {
        // 音量从别的路径改掉（App 内滑块 / DataStore）后，服务调一次这个把系统音量
        // 面板上的数字对齐；它只刷新展示，绝不能反过来再回调 source 造成回环。
        val fake = FakePlayer()
        val player = RemoteVolumePlayer(fake, fake)
        fake.playerVolume = 72

        player.syncFromSource()

        assertEquals(72, player.deviceVolume)
        assertTrue(fake.changes.isEmpty())
    }

    @Test
    fun `非音量调用照常转发给被包装的播放器`() {
        val fake = FakePlayer().apply {
            state = Player.STATE_READY
            ready = true
        }
        val player = RemoteVolumePlayer(fake, fake)

        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.playWhenReady)
    }
}

/**
 * 手写 Fake Player 的设备音量面。除音量方法与 [state]/[ready] 两个转发哨兵外，
 * 其余 Player 方法一律抛 [NotImplementedError]，不放行未预期的转发。
 *
 * 下方大段 override 是按 Media3 1.5.1 `ForwardingPlayer` 方法清单生成的机械样板
 * （脚本生成，非手写）：Kotlin 实现 Java 接口必须补全所有抽象成员，但测试只允许
 * 音量面被调用，其余入口有意全部留成哨兵。
 */
@UnstableApi
private class FakePlayer : Player, DeviceVolumeSource {

    /**
     * 设备音量的"真实所在"（生产里是 USB 控制器 + usbVolumePercent，delegate 与
     * [RemoteVolumePlayer.source] 读写的本来就是同一个值）。Fake 同时扮演 Player 与
     * [DeviceVolumeSource] 两角，保持单一真源，才能如实模拟"步进以 delegate 为基准"。
     */
    var playerVolume: Int = 50

    /** [RemoteVolumePlayer] 每次真的改音量都会回调一次，按顺序记录。 */
    val changes = mutableListOf<Int>()

    /** 非音量面：仅 [非音量调用照常转发给被包装的播放器] 用到。 */
    var state: Int = Player.STATE_IDLE
    var ready: Boolean = false

    override fun currentPercent(): Int = playerVolume

    override fun onVolumeChange(percent: Int) {
        changes += percent
        playerVolume = percent
    }

    override fun getDeviceInfo(): DeviceInfo = DeviceInfo.UNKNOWN
    override fun getDeviceVolume(): Int = playerVolume
    override fun isDeviceMuted(): Boolean = playerVolume == 0

    override fun setDeviceVolume(p0: Int, p1: Int) {
        playerVolume = p0
    }

    override fun increaseDeviceVolume(p0: Int) {
        setDeviceVolume(playerVolume + 1, p0)
    }

    override fun decreaseDeviceVolume(p0: Int) {
        setDeviceVolume(playerVolume - 1, p0)
    }

    override fun setDeviceMuted(p0: Boolean, p1: Int) {
        if (p0) setDeviceVolume(0, p1)
    }

    override fun getPlaybackState(): Int = state
    override fun getPlayWhenReady(): Boolean = ready

    override fun getApplicationLooper(): Looper = unexpected("getApplicationLooper")
    override fun addListener(p0: Player.Listener) = unexpected("addListener")
    override fun removeListener(p0: Player.Listener) = unexpected("removeListener")
    override fun setMediaItems(p0: List<MediaItem>) = unexpected("setMediaItems")
    override fun setMediaItems(p0: List<MediaItem>, p1: Boolean) = unexpected("setMediaItems")
    override fun setMediaItems(p0: List<MediaItem>, p1: Int, p2: Long) = unexpected("setMediaItems")
    override fun setMediaItem(p0: MediaItem) = unexpected("setMediaItem")
    override fun setMediaItem(p0: MediaItem, p1: Long) = unexpected("setMediaItem")
    override fun setMediaItem(p0: MediaItem, p1: Boolean) = unexpected("setMediaItem")
    override fun addMediaItem(p0: MediaItem) = unexpected("addMediaItem")
    override fun addMediaItem(p0: Int, p1: MediaItem) = unexpected("addMediaItem")
    override fun addMediaItems(p0: List<MediaItem>) = unexpected("addMediaItems")
    override fun addMediaItems(p0: Int, p1: List<MediaItem>) = unexpected("addMediaItems")
    override fun moveMediaItem(p0: Int, p1: Int) = unexpected("moveMediaItem")
    override fun moveMediaItems(p0: Int, p1: Int, p2: Int) = unexpected("moveMediaItems")
    override fun replaceMediaItem(p0: Int, p1: MediaItem) = unexpected("replaceMediaItem")
    override fun replaceMediaItems(p0: Int, p1: Int, p2: List<MediaItem>) = unexpected("replaceMediaItems")
    override fun removeMediaItem(p0: Int) = unexpected("removeMediaItem")
    override fun removeMediaItems(p0: Int, p1: Int) = unexpected("removeMediaItems")
    override fun clearMediaItems() = unexpected("clearMediaItems")
    override fun isCommandAvailable(p0: Int): Boolean = unexpected("isCommandAvailable")
    override fun canAdvertiseSession(): Boolean = unexpected("canAdvertiseSession")
    override fun getAvailableCommands(): Player.Commands =
        Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build()
    override fun prepare() = unexpected("prepare")
    override fun getPlaybackSuppressionReason(): Int = unexpected("getPlaybackSuppressionReason")
    override fun isPlaying(): Boolean = unexpected("isPlaying")
    override fun getPlayerError(): PlaybackException = unexpected("getPlayerError")
    override fun play() = unexpected("play")
    override fun pause() = unexpected("pause")
    override fun setPlayWhenReady(p0: Boolean) = unexpected("setPlayWhenReady")
    override fun setRepeatMode(p0: Int) = unexpected("setRepeatMode")
    override fun getRepeatMode(): Int = unexpected("getRepeatMode")
    override fun setShuffleModeEnabled(p0: Boolean) = unexpected("setShuffleModeEnabled")
    override fun getShuffleModeEnabled(): Boolean = unexpected("getShuffleModeEnabled")
    override fun isLoading(): Boolean = unexpected("isLoading")
    override fun seekToDefaultPosition() = unexpected("seekToDefaultPosition")
    override fun seekToDefaultPosition(p0: Int) = unexpected("seekToDefaultPosition")
    override fun seekTo(p0: Long) = unexpected("seekTo")
    override fun seekTo(p0: Int, p1: Long) = unexpected("seekTo")
    override fun getSeekBackIncrement(): Long = unexpected("getSeekBackIncrement")
    override fun seekBack() = unexpected("seekBack")
    override fun getSeekForwardIncrement(): Long = unexpected("getSeekForwardIncrement")
    override fun seekForward() = unexpected("seekForward")
    override fun hasPreviousMediaItem(): Boolean = unexpected("hasPreviousMediaItem")
    override fun seekToPreviousWindow() = unexpected("seekToPreviousWindow")
    override fun seekToPreviousMediaItem() = unexpected("seekToPreviousMediaItem")
    override fun seekToPrevious() = unexpected("seekToPrevious")
    override fun getMaxSeekToPreviousPosition(): Long = unexpected("getMaxSeekToPreviousPosition")
    override fun hasNext(): Boolean = unexpected("hasNext")
    override fun hasNextWindow(): Boolean = unexpected("hasNextWindow")
    override fun hasNextMediaItem(): Boolean = unexpected("hasNextMediaItem")
    override fun next() = unexpected("next")
    override fun seekToNextWindow() = unexpected("seekToNextWindow")
    override fun seekToNextMediaItem() = unexpected("seekToNextMediaItem")
    override fun seekToNext() = unexpected("seekToNext")
    override fun setPlaybackParameters(p0: PlaybackParameters) = unexpected("setPlaybackParameters")
    override fun setPlaybackSpeed(p0: Float) = unexpected("setPlaybackSpeed")
    override fun getPlaybackParameters(): PlaybackParameters = unexpected("getPlaybackParameters")
    override fun stop() = unexpected("stop")
    override fun release() = unexpected("release")
    override fun getCurrentTracks(): Tracks = unexpected("getCurrentTracks")
    override fun getTrackSelectionParameters(): TrackSelectionParameters = unexpected("getTrackSelectionParameters")
    override fun setTrackSelectionParameters(p0: TrackSelectionParameters) = unexpected("setTrackSelectionParameters")
    override fun getMediaMetadata(): MediaMetadata = unexpected("getMediaMetadata")
    override fun getPlaylistMetadata(): MediaMetadata = unexpected("getPlaylistMetadata")
    override fun setPlaylistMetadata(p0: MediaMetadata) = unexpected("setPlaylistMetadata")
    override fun getCurrentManifest(): Object = unexpected("getCurrentManifest")
    override fun getCurrentTimeline(): Timeline = unexpected("getCurrentTimeline")
    override fun getCurrentPeriodIndex(): Int = unexpected("getCurrentPeriodIndex")
    override fun getCurrentWindowIndex(): Int = unexpected("getCurrentWindowIndex")
    override fun getCurrentMediaItemIndex(): Int = unexpected("getCurrentMediaItemIndex")
    override fun getNextWindowIndex(): Int = unexpected("getNextWindowIndex")
    override fun getNextMediaItemIndex(): Int = unexpected("getNextMediaItemIndex")
    override fun getPreviousWindowIndex(): Int = unexpected("getPreviousWindowIndex")
    override fun getPreviousMediaItemIndex(): Int = unexpected("getPreviousMediaItemIndex")
    override fun getCurrentMediaItem(): MediaItem = unexpected("getCurrentMediaItem")
    override fun getMediaItemCount(): Int = unexpected("getMediaItemCount")
    override fun getMediaItemAt(p0: Int): MediaItem = unexpected("getMediaItemAt")
    override fun getDuration(): Long = unexpected("getDuration")
    override fun getCurrentPosition(): Long = unexpected("getCurrentPosition")
    override fun getBufferedPosition(): Long = unexpected("getBufferedPosition")
    override fun getBufferedPercentage(): Int = unexpected("getBufferedPercentage")
    override fun getTotalBufferedDuration(): Long = unexpected("getTotalBufferedDuration")
    override fun isCurrentWindowDynamic(): Boolean = unexpected("isCurrentWindowDynamic")
    override fun isCurrentMediaItemDynamic(): Boolean = unexpected("isCurrentMediaItemDynamic")
    override fun isCurrentWindowLive(): Boolean = unexpected("isCurrentWindowLive")
    override fun isCurrentMediaItemLive(): Boolean = unexpected("isCurrentMediaItemLive")
    override fun getCurrentLiveOffset(): Long = unexpected("getCurrentLiveOffset")
    override fun isCurrentWindowSeekable(): Boolean = unexpected("isCurrentWindowSeekable")
    override fun isCurrentMediaItemSeekable(): Boolean = unexpected("isCurrentMediaItemSeekable")
    override fun isPlayingAd(): Boolean = unexpected("isPlayingAd")
    override fun getCurrentAdGroupIndex(): Int = unexpected("getCurrentAdGroupIndex")
    override fun getCurrentAdIndexInAdGroup(): Int = unexpected("getCurrentAdIndexInAdGroup")
    override fun getContentDuration(): Long = unexpected("getContentDuration")
    override fun getContentPosition(): Long = unexpected("getContentPosition")
    override fun getContentBufferedPosition(): Long = unexpected("getContentBufferedPosition")
    override fun getAudioAttributes(): AudioAttributes = unexpected("getAudioAttributes")
    override fun setVolume(p0: Float) = unexpected("setVolume")
    override fun getVolume(): Float = unexpected("getVolume")
    override fun getVideoSize(): VideoSize = unexpected("getVideoSize")
    override fun getSurfaceSize(): Size = unexpected("getSurfaceSize")
    override fun clearVideoSurface() = unexpected("clearVideoSurface")
    override fun clearVideoSurface(p0: Surface?) = unexpected("clearVideoSurface")
    override fun setVideoSurface(p0: Surface?) = unexpected("setVideoSurface")
    override fun setVideoSurfaceHolder(p0: SurfaceHolder?) = unexpected("setVideoSurfaceHolder")
    override fun clearVideoSurfaceHolder(p0: SurfaceHolder?) = unexpected("clearVideoSurfaceHolder")
    override fun setVideoSurfaceView(p0: SurfaceView?) = unexpected("setVideoSurfaceView")
    override fun clearVideoSurfaceView(p0: SurfaceView?) = unexpected("clearVideoSurfaceView")
    override fun setVideoTextureView(p0: TextureView?) = unexpected("setVideoTextureView")
    override fun clearVideoTextureView(p0: TextureView?) = unexpected("clearVideoTextureView")
    override fun getCurrentCues(): CueGroup = unexpected("getCurrentCues")
    override fun setDeviceVolume(p0: Int) = unexpected("setDeviceVolume")
    override fun increaseDeviceVolume() = unexpected("increaseDeviceVolume")
    override fun decreaseDeviceVolume() = unexpected("decreaseDeviceVolume")
    override fun setDeviceMuted(p0: Boolean) = unexpected("setDeviceMuted")
    override fun setAudioAttributes(p0: AudioAttributes, p1: Boolean) = unexpected("setAudioAttributes")

    private fun unexpected(name: String): Nothing =
        throw NotImplementedError("FakePlayer 未实现 $name，说明测试走了预期外的路径")
}
