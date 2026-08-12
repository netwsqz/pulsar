package com.example.pulsar.playback.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UsbHardwareVolumeControl] 的单元测试：百分比 → UAC2 Feature Unit SET_CUR
 * 字节封包的契约。单位统一是 1/256 dB（与 native `probe_hardware_volume()`
 * 探测到的 GET_RANGE 结果一致）。
 */
class UsbHardwareVolumeControlTest {

    @Test
    fun `percent=0 精确落在硬件最小音量`() {
        val db256 = UsbHardwareVolumeControl.percentToDb256(
            percent = 0, minDb256 = -3200, maxDb256 = 0, stepDb256 = 256,
        )
        assertEquals(-3200, db256)
    }

    @Test
    fun `percent=100 精确落在硬件最大音量`() {
        val db256 = UsbHardwareVolumeControl.percentToDb256(
            percent = 100, minDb256 = -3200, maxDb256 = 0, stepDb256 = 256,
        )
        assertEquals(0, db256)
    }

    @Test
    fun `中间百分比结果落在范围内且对齐到硬件步进`() {
        val min = -3200
        val max = 0
        val step = 256
        for (percent in listOf(1, 17, 50, 83, 99)) {
            val db256 = UsbHardwareVolumeControl.percentToDb256(percent, min, max, step)
            assertTrue("db256=$db256 应落在 [$min,$max]", db256 in min..max)
            assertEquals("db256=$db256 应是 step 的整数倍偏移", 0, (db256 - min) % step)
        }
    }

    @Test
    fun `越界百分比先钳位再映射`() {
        val min = -3200
        val max = 0
        val step = 256
        assertEquals(min, UsbHardwareVolumeControl.percentToDb256(-20, min, max, step))
        assertEquals(max, UsbHardwareVolumeControl.percentToDb256(150, min, max, step))
    }

    @Test
    fun `db256ToPayload 编码零值`() {
        assertArrayEquals(byteArrayOf(0, 0), UsbHardwareVolumeControl.db256ToPayload(0))
    }

    @Test
    fun `db256ToPayload 编码正数小端序`() {
        // 256 = 0x0100 → 小端 [0x00, 0x01]
        assertArrayEquals(byteArrayOf(0x00, 0x01), UsbHardwareVolumeControl.db256ToPayload(256))
    }

    @Test
    fun `db256ToPayload 编码负数补码小端序`() {
        // -256 的 16 位补码是 0xFF00 → 小端 [0x00, 0xFF]
        assertArrayEquals(
            byteArrayOf(0x00, 0xFF.toByte()),
            UsbHardwareVolumeControl.db256ToPayload(-256),
        )
        // -1 的 16 位补码是 0xFFFF → 小端 [0xFF, 0xFF]
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
            UsbHardwareVolumeControl.db256ToPayload(-1),
        )
    }

    @Test
    fun `wValue 固定为主声道音量控制选择子`() {
        // VOLUME_CONTROL(0x02) << 8 | 主声道(0)
        assertEquals(0x0200, UsbHardwareVolumeControl.wValue())
    }

    @Test
    fun `wValue 支持左右声道音量控制选择子`() {
        assertEquals(0x0201, UsbHardwareVolumeControl.wValue(channel = 1))
        assertEquals(0x0202, UsbHardwareVolumeControl.wValue(channel = 2))
    }

    @Test
    fun `wIndex 组合 Feature Unit 与控制接口号`() {
        // featureUnitId=3 << 8 | controlInterfaceNumber=1
        assertEquals(0x0301, UsbHardwareVolumeControl.wIndex(featureUnitId = 3, controlInterfaceNumber = 1))
    }
}
