package com.example.pulsar.playback.usb

import kotlin.math.abs

/**
 * USB 音频格式协商：在设备上报的可用格式里挑一个最贴合请求的格式，
 * 并提供采样率支持判断与就近采样率查询。
 *
 * 纯逻辑、不依赖 Android API，可直接做 JVM 单元测试。协商结果供 native
 * 等时传输泵使用；调用方拿到格式后，再用 [nearestRate] 决定实际要
 * SET_CUR 的采样率。
 *
 * 打分规则（[negotiate]）：
 * - 采样率可支持（[supportsRate]）→ +2（最高权重，顺序敏感）
 * - 位深 == 请求位深 → +1
 * - 声道 == 请求声道 → +1
 * - 同分时选 maxPacketSize 更大的格式（更大的包更稳）
 * - 没有任何一个条件命中（全部 0 分）返回 null
 */
object UsbFormatNegotiator {

    /** 动态可协商格式（采样率列表为空）的采样率合法区间下限。 */
    private const val MIN_SAMPLE_RATE = 8000

    /** 动态可协商格式的采样率合法区间上限。 */
    private const val MAX_SAMPLE_RATE = 768000

    /**
     * 从可用格式里选分数最高的那个。
     *
     * 采样率权重最高（+2），位深/声道各 +1；同分时取 maxPacketSize 更大的；
     * 全部 0 分返回 null。
     */
    fun negotiate(
        available: List<UsbAudioFormat>,
        sampleRate: Int,
        bitsPerSample: Int,
        channels: Int,
    ): UsbAudioFormat? {
        // 升序比较器 + maxWithOrNull（自然序 max）：先比分数，同分比包大小。
        // 注意不能用 compareByDescending + maxWithOrNull —— 降序比较器与
        // maxWithOrNull 的"自然序更大"语义冲突，会选出分数更低的格式。
        return available
            .map { format -> format to score(format, sampleRate, bitsPerSample, channels) }
            .filter { (_, s) -> s > 0 }
            .maxWithOrNull(
                compareBy<Pair<UsbAudioFormat, Int>> { it.second }
                    .thenBy { it.first.maxPacketSize },
            )
            ?.first
    }

    /**
     * 判断某格式是否支持给定采样率。
     *
     * 采样率列表非空（UAC1 固定枚举）：做包含判断；列表为空（UAC2 动态可协商，
     * 或 UAC1 连续范围设备）：落在 8000..768000 合法区间即可。
     */
    fun supportsRate(format: UsbAudioFormat, sampleRate: Int): Boolean =
        if (format.sampleRates.isEmpty()) {
            sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE
        } else {
            format.sampleRates.contains(sampleRate)
        }

    /**
     * 找一个该格式实际能用的采样率。
     *
     * 固定列表：取最接近的，距离相同时取较小的；空列表（动态可协商）：
     * 直接返回请求值；请求值非法（超出合法区间）时优雅返回 null，不抛异常。
     */
    fun nearestRate(format: UsbAudioFormat, sampleRate: Int): Int? {
        if (format.sampleRates.isEmpty()) {
            return if (sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) sampleRate else null
        }
        // 距离用 Long 计算，避免极端请求值下的 Int 溢出。
        return format.sampleRates.minWithOrNull(
            compareBy<Int> { abs(it.toLong() - sampleRate) }.thenBy { it },
        )
    }

    /** 打分：位深 +1、声道 +1、采样率支持 +2。 */
    private fun score(
        format: UsbAudioFormat,
        sampleRate: Int,
        bitsPerSample: Int,
        channels: Int,
    ): Int =
        (if (format.bitsPerSample == bitsPerSample) 1 else 0) +
            (if (format.channels == channels) 1 else 0) +
            (if (supportsRate(format, sampleRate)) 2 else 0)
}
