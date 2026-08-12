package com.example.pulsar.playback

import android.media.AudioDeviceInfo

/**
 * USB 音频输出的纯逻辑，与 Android API 解耦以便单元测试。
 *
 * 判断依据是设备的类型列表（而非 [AudioDeviceInfo] 对象本身），
 * 这样测试里不必构造系统对象。
 */
object UsbAudioOutput {

    /** USB 耳机（带麦克风的一体式耳机/耳麦）。 */
    const val TYPE_USB_HEADSET = AudioDeviceInfo.TYPE_USB_HEADSET

    /** 外接 USB 声卡 / DAC（如小尾巴）。 */
    const val TYPE_USB_DEVICE = AudioDeviceInfo.TYPE_USB_DEVICE

    /**
     * 从输出设备类型列表中找出 USB 音频输出设备；没有则返回 null。
     * 同时存在多个 USB 设备时优先独立声卡/DAC（TYPE_USB_DEVICE，如小尾巴），
     * 其次才是 USB 耳机（TYPE_USB_HEADSET）——因为"USB 独占输出"的诉求
     * 通常是接独立 DAC，避免路由漂移到耳机。
     */
    fun findUsbOutputDeviceType(deviceTypes: List<Int>): Int? =
        deviceTypes.firstOrNull { it == TYPE_USB_DEVICE }
            ?: deviceTypes.firstOrNull { it == TYPE_USB_HEADSET }

    /** 开关开启且检测到 USB 输出设备时，才把播放器路由到 USB。 */
    fun shouldRouteToUsb(outputEnabled: Boolean, usbDevicePresent: Boolean): Boolean =
        outputEnabled && usbDevicePresent
}
