package com.example.pulsar.playback.usb

/**
 * 解析 USB 配置描述符（configuration descriptor）的字节流，提取所有
 * 可用的 USB 音频流格式。
 *
 * 纯函数式解析：输入是 [UsbDeviceConnection.controlTransfer] 拉回的原始
 * 描述符字节（GET_DESCRIPTOR / USB_DT_CONFIG），输出是 [UsbAudioFormat] 列表，
 * 供协商与 native 等时传输泵使用。不依赖 Android 硬件，可做单元测试。
 *
 * 解析范围：
 * - 音频流接口（class=1, subclass=2）的 AS_GENERAL / FORMAT_TYPE 类特定
 *   描述符与数据输出端点 → 完整格式参数（含 UAC1 的 bSubframeSize）。
 * - UAC2 的 AudioControl 接口（subclass=1）：解析「输入终端 → 时钟源」映射，
 *   通过流接口 AS_GENERAL 的 bTerminalLink 反查出采样率控制所需的时钟源 ID；
 *   同时解析播放链路上的 Feature Unit 主音量可读写能力。
 * - AudioControl 其余实体、反馈端点、其它类不做处理。
 */
object UsbDescriptorParser {

    private const val DESC_CONFIGURATION = 0x02
    private const val DESC_INTERFACE = 0x04
    private const val DESC_ENDPOINT = 0x05
    private const val DESC_INTERFACE_ASSOCIATION = 0x0B
    private const val DESC_CLASS_SPECIFIC = 0x24

    private const val AUDIO_CLASS = 0x01
    private const val AUDIO_CONTROL_SUBCLASS = 0x01
    private const val AUDIO_STREAMING_SUBCLASS = 0x02
    private const val UAC2_PROTOCOL = 0x20

    private const val AS_GENERAL = 0x01
    private const val FORMAT_TYPE = 0x02
    private const val INPUT_TERMINAL = 0x02
    private const val FEATURE_UNIT = 0x06
    private const val UAC2_VOLUME_CONTROL_SHIFT = 2
    private const val UAC2_CONTROL_MASK = 0x03
    private const val UAC2_CONTROL_READ_WRITE = 0x03

    /** 解析配置描述符字节流，返回按出现顺序排列的可用音频格式。 */
    fun parse(configurationDescriptor: ByteArray): List<UsbAudioFormat> {
        val bytes = configurationDescriptor
        val totalLength = if (bytes.size >= 4 && bytes[0].toInt() == 9 && bytes[1].toInt() == DESC_CONFIGURATION) {
            minOf(u16(bytes, 2), bytes.size)
        } else {
            bytes.size
        }

        val groups = mutableListOf<StreamGroup>()
        val functions = mutableListOf<AudioFunction>()
        var currentFunction = AudioFunction(firstInterface = 0, interfaceCount = Int.MAX_VALUE)
        functions += currentFunction
        var group: StreamGroup? = null
        var inAcInterface = false
        var acIsUac2 = false
        var offset = 0
        while (offset + 1 < totalLength) {
            val bLength = bytes[offset].toInt() and 0xFF
            if (bLength == 0) break
            if (offset + bLength > totalLength) break
            val bType = bytes[offset + 1].toInt() and 0xFF

            when (bType) {
                DESC_INTERFACE_ASSOCIATION -> {
                    if (bLength >= 8) {
                        val cls = bytes[offset + 4].toInt() and 0xFF
                        if (cls == AUDIO_CLASS) {
                            currentFunction = AudioFunction(
                                firstInterface = bytes[offset + 2].toInt() and 0xFF,
                                interfaceCount = bytes[offset + 3].toInt() and 0xFF,
                            )
                            functions += currentFunction
                        }
                    }
                }

                DESC_INTERFACE -> {
                    // 新接口描述符：先封口当前音频流分组，再建立新上下文。
                    group?.takeIf { it.isComplete() }?.let { groups += it }
                    val cls = bytes[offset + 5].toInt() and 0xFF
                    val subclass = bytes[offset + 6].toInt() and 0xFF
                    val protocol = bytes[offset + 7].toInt() and 0xFF
                    val interfaceNumber = bytes[offset + 2].toInt() and 0xFF
                    currentFunction = functions.lastOrNull { it.contains(interfaceNumber) }
                        ?: currentFunction
                    inAcInterface = cls == AUDIO_CLASS && subclass == AUDIO_CONTROL_SUBCLASS
                    acIsUac2 = inAcInterface && protocol == UAC2_PROTOCOL
                    if (inAcInterface) {
                        currentFunction.controlInterfaceNumber = interfaceNumber
                    }
                    group = if (cls == AUDIO_CLASS && subclass == AUDIO_STREAMING_SUBCLASS) {
                        StreamGroup(
                            interfaceNumber = interfaceNumber,
                            altSetting = bytes[offset + 3].toInt() and 0xFF,
                            uacVersion = if (protocol == UAC2_PROTOCOL) UsbAudioFormat.UAC_VERSION_2
                            else UsbAudioFormat.UAC_VERSION_1,
                            audioFunction = currentFunction,
                        )
                    } else {
                        null
                    }
                }

                DESC_CLASS_SPECIFIC -> {
                    if (group != null) {
                        val subtype = bytes[offset + 2].toInt() and 0xFF
                        when (subtype) {
                            AS_GENERAL -> parseAsGeneral(bytes, offset, bLength, group)
                            FORMAT_TYPE -> parseFormatType(bytes, offset, bLength, group)
                        }
                    } else if (acIsUac2) {
                        // UAC2 AudioControl 接口：记录输入终端 → 时钟源映射。
                        val subtype = bytes[offset + 2].toInt() and 0xFF
                        when (subtype) {
                            INPUT_TERMINAL -> if (bLength >= 8) {
                                currentFunction.terminalClock[
                                    bytes[offset + 3].toInt() and 0xFF
                                ] = bytes[offset + 7].toInt() and 0xFF
                            }
                            FEATURE_UNIT -> if (bLength >= 10) {
                                val controlCount = (bLength - 6) / 4
                                var writableVolumeChannels = 0
                                for (channel in 0 until controlCount) {
                                    val controlOffset = offset + 5 + channel * 4
                                    if (controlOffset + 4 > offset + bLength - 1) break
                                    val controls = u32(bytes, controlOffset)
                                    val volumeAccess =
                                        (controls shr UAC2_VOLUME_CONTROL_SHIFT) and UAC2_CONTROL_MASK
                                    if (volumeAccess == UAC2_CONTROL_READ_WRITE && channel < Int.SIZE_BITS) {
                                        writableVolumeChannels = writableVolumeChannels or (1 shl channel)
                                    }
                                }
                                currentFunction.featureUnits += FeatureUnit(
                                    unitId = bytes[offset + 3].toInt() and 0xFF,
                                    sourceId = bytes[offset + 4].toInt() and 0xFF,
                                    writableVolumeChannels = writableVolumeChannels,
                                )
                            }
                        }
                    }
                }

                DESC_ENDPOINT -> {
                    group?.let { g ->
                        val addr = bytes[offset + 2].toInt() and 0xFF
                        // 只记录音频数据输出端点（OUT 方向，bit7==0）。
                        if (addr and 0x80 == 0) {
                            g.endpointAddress = addr
                            g.maxPacketSize = u16(bytes, offset + 4)
                            g.bInterval = bytes[offset + 6].toInt() and 0xFF
                            if (bLength >= 9) g.bSynchAddress = bytes[offset + 8].toInt() and 0xFF
                        }
                    }
                }
            }
            offset += bLength
        }
        group?.takeIf { it.isComplete() }?.let { groups += it }

        // 时钟解析放到最后：AC 接口理论上先于流接口出现，但描述符顺序不保证，
        // 统一在收尾阶段用完整的映射表反查，避免漏解析。控制接口号同理。
        return groups.map {
            val function = it.audioFunction
            it.toFormat(
                clockId = function.terminalClock[it.bTerminalLink] ?: 0,
                controlInterfaceNumber = function.controlInterfaceNumber,
                volumeControl = findFeatureUnit(
                    terminalId = it.bTerminalLink,
                    featureUnits = function.featureUnits,
                ),
            )
        }
    }

    /** 类特定描述符在非音频流接口时跳过（避免 when 分支重复）。 */
    private fun parseAsGeneral(bytes: ByteArray, offset: Int, bLength: Int, g: StreamGroup) {
        // bTerminalLink（UAC1/UAC2 都在索引 3）：指向 AC 接口的输入终端，反查时钟源用。
        if (bLength >= 4) g.bTerminalLink = bytes[offset + 3].toInt() and 0xFF
        if (g.uacVersion == UsbAudioFormat.UAC_VERSION_2 && bLength >= 11) {
            // UAC2 AS_GENERAL（规范 Table 4-27）：bNrChannels 在索引 10，紧随其后的
            // 索引 11-14 是 bmChannelConfig（4 字节）。曾误读索引 11，把立体声的
            // bmChannelConfig=0x00000003 低字节当成 3 声道——务必读索引 10。
            g.channels = bytes[offset + 10].toInt() and 0xFF
        }
        // UAC1：声道/位深都在 FORMAT_TYPE，AS_GENERAL 只含 wFormatTag，无需处理。
    }

    private fun parseFormatType(bytes: ByteArray, offset: Int, bLength: Int, g: StreamGroup) {
        when (g.uacVersion) {
            UsbAudioFormat.UAC_VERSION_1 -> {
                if (bLength < 8) return
                // FORMAT_TYPE_I（PCM）：bNrChannels=4, bSubframeSize=5, bBitResolution=6, bSamFreqType=7
                g.channels = bytes[offset + 4].toInt() and 0xFF
                g.subframeSize = bytes[offset + 5].toInt() and 0xFF
                g.bitsPerSample = bytes[offset + 6].toInt() and 0xFF
                val freqType = bytes[offset + 7].toInt() and 0xFF
                var p = offset + 8
                if (freqType > 0) {
                    repeat(freqType) {
                        if (p + 3 <= offset + bLength) {
                            val rate = (bytes[p].toInt() and 0xFF) or
                                ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                                ((bytes[p + 2].toInt() and 0xFF) shl 16)
                            g.sampleRates.add(rate)
                        }
                        p += 3
                    }
                }
                // freqType==0 表示连续范围（wMin..wMax），枚举不了，保持空列表——
                // 协商时对这种设备尝试 SET_CUR 任意值。
            }
            UsbAudioFormat.UAC_VERSION_2 -> {
                if (bLength < 6) return
                // FORMAT_TYPE_I（PCM）：bSubslotSize=4, bBitResolution=5
                g.subframeSize = bytes[offset + 4].toInt() and 0xFF
                g.bitsPerSample = bytes[offset + 5].toInt() and 0xFF
            }
        }
    }

    private class AudioFunction(
        private val firstInterface: Int,
        private val interfaceCount: Int,
    ) {
        var controlInterfaceNumber: Int = 0
        val terminalClock = mutableMapOf<Int, Int>()
        val featureUnits = mutableListOf<FeatureUnit>()

        fun contains(interfaceNumber: Int): Boolean =
            interfaceNumber >= firstInterface && interfaceNumber < firstInterface + interfaceCount
    }

    private data class FeatureUnit(
        val unitId: Int,
        val sourceId: Int,
        val writableVolumeChannels: Int,
    )

    private data class VolumeControl(val featureUnitId: Int, val channelMask: Int)

    /** 从流链接的终端沿 Feature Unit source 链寻找最近的可写音量单元。 */
    private fun findFeatureUnit(terminalId: Int, featureUnits: List<FeatureUnit>): VolumeControl? {
        var entityId = terminalId
        val visited = mutableSetOf<Int>()
        while (visited.add(entityId)) {
            val unit = featureUnits.firstOrNull { it.sourceId == entityId } ?: return null
            if (unit.writableVolumeChannels != 0) {
                return VolumeControl(unit.unitId, unit.writableVolumeChannels)
            }
            entityId = unit.unitId
        }
        return null
    }

    /** 内部累积器：一个音频流 alt setting 的解析结果。 */
    private class StreamGroup(
        val interfaceNumber: Int,
        val altSetting: Int,
        val uacVersion: Int,
        val audioFunction: AudioFunction,
    ) {
        var channels: Int = 0
        var bitsPerSample: Int = 0
        var subframeSize: Int = 0
        var bTerminalLink: Int = 0
        val sampleRates = mutableListOf<Int>()
        var endpointAddress: Int = -1
        var maxPacketSize: Int = 0
        var bInterval: Int = 0
        var bSynchAddress: Int = 0

        /** 需要：非零带宽（alt>=1）、数据端点、声道与位深已解析。 */
        fun isComplete(): Boolean =
            altSetting >= 1 && endpointAddress >= 0 && channels > 0 && bitsPerSample > 0

        fun toFormat(
            clockId: Int,
            controlInterfaceNumber: Int,
            volumeControl: VolumeControl?,
        ): UsbAudioFormat = UsbAudioFormat(
            uacVersion = uacVersion,
            interfaceNumber = interfaceNumber,
            altSetting = altSetting,
            endpointAddress = endpointAddress,
            maxPacketSize = maxPacketSize,
            bInterval = bInterval,
            bSynchAddress = bSynchAddress,
            channels = channels,
            bitsPerSample = bitsPerSample,
            sampleRates = sampleRates.toList(),
            subframeSize = subframeSize,
            clockId = clockId,
            controlInterfaceNumber = controlInterfaceNumber,
            featureUnitId = volumeControl?.featureUnitId ?: 0,
            volumeChannelMask = volumeControl?.channelMask ?: 0,
        )
    }

    /** little-endian uint16。 */
    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /** little-endian uint32；仅用于长度已校验的 UAC2 bmaControls。 */
    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
