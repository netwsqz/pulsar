package com.example.pulsar.playback.usb

/**
 * 软件数字衰减路径的听感音量曲线（立方 taper）。
 *
 * 人耳对响度的感知接近立方/对数曲线，不是线性——真机验证发现，线性映射下用户
 * 日常听音量落在滑块 1%-2% 这一段，分辨率差到几乎调不准。套用立方曲线后，
 * 同样的听感音量会落在滑块中段（约 20%-30%），可用档位更多，容易微调。
 *
 * 仅用于 native `refill()` 的软件线性 PCM 衰减路径（[UsbNativeDriver.nativeSetVolume]）；
 * 硬件 SET_CUR 走 dB（本身是对数量纲），不套这条曲线。
 */
object UsbVolumeCurve {

    /** percent 0..100 经立方 taper 映射后仍是 0..100，供 native 软件衰减使用。 */
    fun apply(percent: Int): Int {
        val clamped = percent.coerceIn(0, 100)
        val fraction = clamped / 100.0
        return Math.round(fraction * fraction * fraction * 100.0).toInt()
    }
}
