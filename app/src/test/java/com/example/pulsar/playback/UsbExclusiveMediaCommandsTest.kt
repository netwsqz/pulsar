package com.example.pulsar.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UsbExclusiveMediaCommands.isUsbAudioActive]：音量键接管的前提条件。
 *
 * 独占开关只代表"用户想让声音走 USB"。没插设备 / 泵还没起来时声音实际走系统混音器，
 * 此时接管音量键只会调到一个对实际出声无效的 USB 音量——用户看到的就是"音量键失灵"
 * （真机反馈：独占开着但没插 DAC）。
 */
class UsbExclusiveMediaCommandsTest {

    @Test
    fun `只有 streaming 状态算作 USB 泵在实际输出`() {
        assertTrue(UsbExclusiveMediaCommands.isUsbAudioActive("streaming"))
    }

    @Test
    fun `其余状态一律不算实际输出`() {
        listOf("disabled", "no_device", "awaiting_permission", "ready", "error", "", "unknown")
            .forEach { state ->
                assertFalse(state, UsbExclusiveMediaCommands.isUsbAudioActive(state))
            }
    }
}
