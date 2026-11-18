package com.example.pulsar.state

import com.example.pulsar.AlbumPickerState
import com.example.pulsar.data.qqdownload.QQJobDto
import com.example.pulsar.data.qqdownload.QQSongDto

/**
 * QQ 下载（入队到电脑）的 slice（`docs/qq-download-state-refactor.md` / #31）：
 * 任务列表、已在库 / 下载中标记、专辑入队进度、两个弹窗与动作提示。
 *
 * ⚠️ 名字带 `QQ` 不是装饰：`com.example.pulsar.DownloadState` 是手机端下载调度器的
 * 单任务进度，两块状态毫无关系，别合并。
 *
 * 轮询本身不进状态：它由 `QQDownloadViewModel` 的 job + 代次管，界面只看 [jobs]
 * 里还有没有 queued/running。
 */
data class QQDownloadState(
    /** 服务端队列的完整快照（每轮整体替换；完成的会自动消失）。 */
    val jobs: List<QQJobDto> = emptyList(),
    /**
     * 服务端最新一次回传的「下载中」标记（每次响应整体替换）。
     * 渲染一律用 [activeDownloadingMids]，别直接用它——刚点下去还没轮询到时它是旧的。
     */
    val downloadingMids: Set<String> = emptySet(),
    /**
     * 已在库的曲目（键 song_mid）：搜索/弹窗响应、任务完成、服务端跳过都并进来；
     * 服务生命周期内只增不减，换服务时随 [cleared] 整块作废。
     */
    val inLibraryMids: Set<String> = emptySet(),
    val isEnqueuingAlbum: Boolean = false,
    /** 正在入队的专辑 mid：只有对应专辑行显示转圈，避免多专辑同时点全转。 */
    val enqueuingAlbumMid: String? = null,
    /** 专辑选曲弹窗；null = 未打开。 */
    val albumPicker: AlbumPickerState? = null,
    /** 非 null = 用户点了已在库曲目的下载图标，等确认「仍要重新下载」。 */
    val redownloadPrompt: QQSongDto? = null,
    /** 下载动作的反馈（音质不可用 / 入队失败 / 整专跳过 N 首）：Snackbar，显示完即清。 */
    val actionMessage: String? = null,
) {
    /**
     * 正在下载的 mid：服务端最新标记 ∪ 本地任务队列 queued/running
     * （刚点下去还没轮询到也要立刻准）。派生只在这里算一次。
     */
    val activeDownloadingMids: Set<String>
        get() = downloadingMids + jobs.filter { it.isActive }.map { it.songMid }.toSet()

    /**
     * 换服务时整块作废（任务/弹窗/标记都属于上一条服务）。
     * 默认值即已清，新增字段自动跟上（`QQDownloadStateClearedTest` 用反射钉死）。
     */
    fun cleared(): QQDownloadState = QQDownloadState()
}

/**
 * 任务还在队列里（排队 / 下载中）。
 *
 * 判断出现在三处：派生 [QQDownloadState.activeDownloadingMids]、应用任务快照、
 * 决定轮询是否继续——字符串状态只在这里认一次，别在各处重抄 `"queued" || "running"`。
 */
internal val QQJobDto.isActive: Boolean
    get() = status == "queued" || status == "running"
