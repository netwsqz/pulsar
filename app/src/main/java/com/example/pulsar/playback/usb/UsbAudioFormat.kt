package com.example.pulsar.playback.usb

/**
 * USB 音频流接口的一个可用格式（alt setting）。
 *
 * 由 [UsbDescriptorParser] 从配置描述符解析而来，是协商（[UsbFormatNegotiator]）
 * 和 native 等时传输泵的输入参数来源。
 *
 * 纯数据类，不依赖 Android API，便于单元测试。
 *
 * @param uacVersion USB Audio Class 版本：0x0100（UAC1）/ 0x0200（UAC2）。
 *   决定采样率协商走哪套协议（UAC2 设时钟实体、UAC1 设端点）。
 * @param interfaceNumber 流接口号。
 * @param altSetting 备用接口号（>=1 才有实际格式；0 是零带宽占位）。
 * @param endpointAddress 音频数据输出端点的地址（bit7=0 表示 OUT 方向）。
 * @param maxPacketSize 每个等时包的最大字节数（wMaxPacketSize）。
 * @param bInterval 等时传输间隔：USB 高速下 2^(bInterval-1) 微帧，全速下为毫秒。
 * @param bSynchAddress 反馈端点地址；0 表示设备无反馈端点（async/adaptive 需反馈，
 *   无则按自适应节奏发包）。
 * @param channels 声道数。
 * @param bitsPerSample 位深（16/24/32）。
 * @param sampleRates 该格式支持的采样率。UAC1 直接从描述符读取；UAC2 的采样率
 *   挂在时钟实体上需运行时查询，故此处为空列表，由协商时 GET_RANGE 补充。
 * @param subframeSize 每声道每帧字节数（UAC1 bSubframeSize / UAC2 bSubslotSize，
 *   常见 2/3/4）。0 表示描述符未解析到，计算每帧字节数时按位深向上取整兜底。
 * @param clockId UAC2 采样率控制所在的时钟源（Clock Source）ID，由描述符里
 *   输入终端 → 时钟源的映射解析；UAC1 采样率在端点上、无时钟实体，恒为 0。
 * @param controlInterfaceNumber AudioControl（AC）接口号。UAC2 SET_CUR 采样率
 *   控制传输的 wIndex 低字节必须填它（wIndex = clockId<<8 | 控制接口号）；
 *   UAC1 采样率 SET_CUR 发往端点、无控制接口，恒为 0。
 * @param featureUnitId UAC2 播放链路上声明至少一个声道主音量可读写的 Feature Unit ID；
 *   0 表示描述符未声明可设置的硬件音量。
 * @param volumeChannelMask UAC2 Feature Unit 中支持可写 Volume 的声道位掩码。 */
data class UsbAudioFormat(
    val uacVersion: Int,
    val interfaceNumber: Int,
    val altSetting: Int,
    val endpointAddress: Int,
    val maxPacketSize: Int,
    val bInterval: Int,
    val bSynchAddress: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val sampleRates: List<Int>,
    val subframeSize: Int = 0,
    val clockId: Int = 0,
    val controlInterfaceNumber: Int = 0,
    val featureUnitId: Int = 0,
    /** 位掩码：bit N 表示 UAC2 Feature Unit 的声道 N 支持可写 Volume。 */
    val volumeChannelMask: Int = 0,
) {
    val isUac2: Boolean get() = uacVersion == UAC_VERSION_2
    val hasHardwareVolume: Boolean get() = featureUnitId != 0 && volumeChannelMask != 0

    companion object {
        const val UAC_VERSION_1 = 0x0100
        const val UAC_VERSION_2 = 0x0200
    }
}
