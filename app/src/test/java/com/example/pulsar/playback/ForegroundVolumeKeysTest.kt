package com.example.pulsar.playback

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ForegroundVolumeKeys] 的行为规格：Activity 在前台时把音量键截下来驱动 USB 独占音量。
 *
 * 背景见 docs/usb-volume-key-interception.md §10：MIUI 保留了 `setPlaybackToRemote` 的
 * API 却删掉了音量键派发，MediaSession 那条路收不到按键，只有前台截获这一条路可用。
 *
 * 这里的判据只有一条："这次按键事件要不要吃掉（不让系统去调它自己的音量）"。
 */
class ForegroundVolumeKeysTest {

    private val keys = ForegroundVolumeKeys()

    /** 安装状态快照与下发口，返回记录每一次下发的步进列表。 */
    private fun install(
        usbAudioActive: Boolean = true,
        sinkAvailable: Boolean = true,
    ): MutableList<Int> {
        val steps = mutableListOf<Int>()
        keys.snapshot = ForegroundVolumeKeys.Snapshot(usbAudioActive = usbAudioActive)
        keys.sink = ForegroundVolumeKeys.Sink { step ->
            if (!sinkAvailable) return@Sink false
            steps += step
            true
        }
        return steps
    }

    @Test
    fun `独占开启时吃掉音量加键并下发加一档`() {
        val steps = install()

        val consumed = keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN)

        assertTrue(consumed)
        assertEquals(listOf(1), steps)
    }

    @Test
    fun `独占开启时吃掉音量减键并下发减一档`() {
        val steps = install()

        val consumed = keys.handle(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN)

        assertTrue(consumed)
        assertEquals(listOf(-1), steps)
    }

    @Test
    fun `USB 泵没在输出时音量键立刻还给系统`() {
        // 独占开关开着但没插设备（或泵未就绪）：声音走系统 AudioTrack，此时接管只会去改
        // 一个对出声无效的 USB 音量 —— 用户看到的就是音量键失灵。
        val steps = install(usbAudioActive = false)

        assertFalse(keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        assertFalse(keys.handle(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `状态尚未就绪时放行音量键`() {
        // UI 层每 500ms 才回填一次快照，服务未连上时 snapshot 还是 null——此时不能接管，
        // 否则按键会被无声吞掉。
        val steps = install()
        keys.snapshot = null

        assertFalse(keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `下发口未安装时放行音量键`() {
        val steps = install()
        keys.sink = null

        assertFalse(keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `命令不可用时放行音量键`() {
        // 会话没挂上设备音量命令时下发会失败；这时宁可让音量键恢复系统行为，也不要
        // 吃掉按键却什么都不做。
        val steps = install(sinkAvailable = false)

        assertFalse(keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `非音量键一律放行`() {
        install()

        assertFalse(keys.handle(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN))
        assertFalse(keys.handle(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN))
        assertFalse(keys.handle(KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.ACTION_DOWN))
    }

    @Test
    fun `长按重复的按下事件每次都下发一档`() {
        // 长按靠系统按输入重复率连续派发 ACTION_DOWN，这里不能用去重把后面的吃掉，
        // 否则长按只会走一档。
        val steps = install()

        repeat(3) { keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN) }

        assertEquals(listOf(1, 1, 1), steps)
    }

    @Test
    fun `抬起事件跟着同一次按下的选择走`() {
        // 只吃 DOWN 不吃 UP，系统会在 UP 时补一次自己的音量调整；只吃 UP 不吃 DOWN，
        // DOWN 已经落到系统手里了，同样拦不住。两边必须一致。
        val steps = install()

        assertTrue(keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        assertTrue(keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP))
        assertEquals(listOf(1), steps)
    }

    @Test
    fun `USB 泵没在输出时抬起事件也还给系统`() {
        val steps = install(usbAudioActive = false)

        assertFalse(keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        assertFalse(keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP))
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `命令不可用时按下与抬起都放行`() {
        // 下发失败说明当前根本接管不了；这时连抬起事件也一并放行，不能出现"吃了一半"
        // 的半接管状态（DOWN 落到系统、UP 被 App 吃掉，系统行为会变得莫名其妙）。
        val steps = install(sinkAvailable = false)

        assertFalse(keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        assertFalse(keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP))
        assertTrue(steps.isEmpty())
    }
}
