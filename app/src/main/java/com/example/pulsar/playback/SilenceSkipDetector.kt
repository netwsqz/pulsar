package com.example.pulsar.playback

/**
 * 静音跳曲检测的可调参数。
 *
 * - [thresholdDb]：电平低于此值（dBFS）视为静音。
 * - [requiredDurationMs]：静音需连续持续多久才判定应跳曲，避免误伤瞬时安静片段。
 * - [tailWindowMs]：只在曲目剩余时长小于此值时才启用检测，避免误杀歌曲中间的安静段落。
 */
data class SilenceSkipConfig(
    val thresholdDb: Double,
    val requiredDurationMs: Long,
    val tailWindowMs: Long
)

/**
 * 静音跳曲检测的状态。
 *
 * [silenceStartMs] 记录当前静音段起始的曲内位置（毫秒）；null 表示当前不处于静音段中
 * （或已被打断 / 已跳出窗口重置）。调用方每次 evaluate 后用返回的 state 替换旧状态。
 */
data class SilenceSkipState(val silenceStartMs: Long? = null)

/** [SilenceSkipDetector.evaluate] 的结果：是否应该立即跳到下一曲，以及更新后的状态。 */
data class SilenceSkipResult(val shouldSkip: Boolean, val state: SilenceSkipState)

/**
 * 纯函数式静音跳曲检测器：只在曲目末尾窗口（[SilenceSkipConfig.tailWindowMs]）内累积静音
 * 时长，连续静音达到 [SilenceSkipConfig.requiredDurationMs] 才判定应跳曲（硬切）。
 *
 * 不依赖 Android API，方便单测；真机电平采集与实际 seekToNext 触发见其它接入层。
 */
object SilenceSkipDetector {
    fun evaluate(
        state: SilenceSkipState,
        levelDb: Double,
        positionMs: Long,
        durationMs: Long?,
        config: SilenceSkipConfig
    ): SilenceSkipResult {
        // 无法确定时长（流未知/异常）时不做任何判断，避免误判。
        if (durationMs == null || durationMs <= 0) {
            return SilenceSkipResult(shouldSkip = false, state = SilenceSkipState())
        }

        val inTailWindow = positionMs >= durationMs - config.tailWindowMs
        if (!inTailWindow) {
            return SilenceSkipResult(shouldSkip = false, state = SilenceSkipState())
        }

        val isSilent = levelDb <= config.thresholdDb
        if (!isSilent) {
            return SilenceSkipResult(shouldSkip = false, state = SilenceSkipState())
        }

        val silenceStartMs = state.silenceStartMs ?: positionMs
        val elapsed = positionMs - silenceStartMs
        val shouldSkip = elapsed >= config.requiredDurationMs
        return SilenceSkipResult(shouldSkip, SilenceSkipState(silenceStartMs))
    }
}
