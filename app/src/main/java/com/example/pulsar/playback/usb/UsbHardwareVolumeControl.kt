package com.example.pulsar.playback.usb

/**
 * UAC2 Feature Unit 主音量 SET_CUR 控制传输的纯函数封包逻辑。
 *
 * 与 native `set_sample_rate()`/`probe_hardware_volume()`（`usb_audio_driver.cpp`）
 * 用同一套 UAC2 wIndex/wValue 编码惯例，但音量控制传输在 Kotlin 层直接用
 * [android.hardware.usb.UsbDeviceConnection.controlTransfer] 发送，不经过 native/JNI
 * ——这是低频操作（用户松手滑块才发一次），不需要 native 的实时保证。
 *
 * 单位统一是 1/256 dB，与 native `probe_hardware_volume()` 探测到的 GET_RANGE
 * 结果（[UsbNativeDriver.nativeGetHardwareVolumeRange]）一致。
 */
object UsbHardwareVolumeControl {

    /** SET_CUR 控制传输：host→device | class | interface（与 native UAC2 分支一致）。 */
    const val REQUEST_TYPE_SET_CUR = 0x21

    /** UAC bRequest：CUR（当前值）。 */
    const val REQUEST_SET_CUR = 0x01

    /** UAC2 Volume Control 选择子。 */
    private const val VOLUME_CONTROL_SELECTOR = 0x02

    /** 只控制主声道，与 native `probe_hardware_volume()` 探测时用的声道一致。 */
    private const val MASTER_CHANNEL = 0

    /**
     * 百分比 0..100 线性映射到 `[minDb256, maxDb256]`，再按 `stepDb256` 对齐到
     * 硬件档位——UAC2 设备通常只接受 `min + n*step` 的值，任意值可能被拒绝或钳位。
     *
     * `percent` 越界先钳位；`percent=0`/`percent=100` 精确返回 `minDb256`/`maxDb256`
     * （即使 `(maxDb256-minDb256)` 不是 `stepDb256` 的整数倍，也保证两端精确可达）。
     */
    fun percentToDb256(percent: Int, minDb256: Int, maxDb256: Int, stepDb256: Int): Int {
        require(minDb256 <= maxDb256) { "minDb256 必须 <= maxDb256" }
        require(stepDb256 > 0) { "stepDb256 必须为正数" }

        val clamped = percent.coerceIn(0, 100)
        if (clamped == 0) return minDb256
        if (clamped == 100) return maxDb256

        val range = maxDb256 - minDb256
        val totalSteps = range / stepDb256
        if (totalSteps <= 0) return minDb256

        val stepIndex = Math.round(totalSteps.toDouble() * clamped / 100.0)
            .toInt()
            .coerceIn(0, totalSteps)
        return minDb256 + stepIndex * stepDb256
    }

    /** dB256（有符号）编码为 UAC2 要求的小端 2 字节负载（有符号 16 位）。 */
    fun db256ToPayload(db256: Int): ByteArray {
        val value = db256.toShort().toInt()
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
        )
    }

    /** wValue = VOLUME_CONTROL(0x02) << 8 | channel。 */
    fun wValue(channel: Int = MASTER_CHANNEL): Int {
        require(channel in 0..0xFF) { "channel 必须在 0..255" }
        return (VOLUME_CONTROL_SELECTOR shl 8) or channel
    }

    /**
     * wIndex = featureUnitId << 8 | controlInterfaceNumber，与 native 侧
     * `probe_hardware_volume()`/`set_sample_rate()` 的 wIndex 编码方式一致
     * （实体 ID 在高字节，接口号在低字节）。
     */
    fun wIndex(featureUnitId: Int, controlInterfaceNumber: Int): Int =
        (featureUnitId shl 8) or controlInterfaceNumber
}
