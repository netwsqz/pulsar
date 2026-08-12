package com.example.pulsar.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UsbVolumeCurve] 的单元测试：软件数字衰减路径的听感曲线契约。
 *
 * 人耳对响度的感知接近对数/立方曲线，不是线性——真机验证发现，线性映射下
 * 用户日常听音量落在滑块 1%-2% 这一段，分辨率差到几乎调不准。改用立方 taper
 * （`curved = (percent/100)^3 * 100`）把同样的听感音量挪到滑块中段，可用档位
 * 更多。仅用于 native 软件线性衰减路径；硬件 SET_CUR 走 dB（本身对数量纲），
 * 不套这条曲线。
 */
class UsbVolumeCurveTest {

    @Test
    fun `percent=0 映射为 0`() {
        assertEquals(0, UsbVolumeCurve.apply(0))
    }

    @Test
    fun `percent=100 映射为 100`() {
        assertEquals(100, UsbVolumeCurve.apply(100))
    }

    @Test
    fun `percent=50 立方映射为 13`() {
        // (0.5)^3 * 100 = 12.5 → 四舍五入 13
        assertEquals(13, UsbVolumeCurve.apply(50))
    }

    @Test
    fun `低滑块位置映射到更小但可分辨的档位`() {
        // (0.27)^3 * 100 ≈ 1.968 → 2；(0.30)^3 * 100 = 2.7 → 3
        assertEquals(2, UsbVolumeCurve.apply(27))
        assertEquals(3, UsbVolumeCurve.apply(30))
    }

    @Test
    fun `曲线在 0 到 100 全程单调不减`() {
        var previous = -1
        for (percent in 0..100) {
            val curved = UsbVolumeCurve.apply(percent)
            assertTrue(
                "percent=$percent 的映射值 $curved 应不小于上一档 $previous",
                curved >= previous,
            )
            previous = curved
        }
    }

    @Test
    fun `结果始终落在 0 到 100 之间`() {
        for (percent in 0..100) {
            val curved = UsbVolumeCurve.apply(percent)
            assertTrue("percent=$percent 映射结果 $curved 越界", curved in 0..100)
        }
    }

    @Test
    fun `越界百分比先钳位再映射`() {
        assertEquals(0, UsbVolumeCurve.apply(-20))
        assertEquals(100, UsbVolumeCurve.apply(150))
    }
}
