package com.example.pulsar.state

import com.example.pulsar.data.qqdownload.QQAlbumDto
import com.example.pulsar.data.qqdownload.QQSongDto

/**
 * QQ 搜索的 slice（`docs/qq-download-state-refactor.md` / #31）：查询词、结果、在途与错误。
 *
 * 字段名去掉 `search` 前缀（`query` / `error`），与曲库那条线的 [SearchState] 同一口径。
 *
 * **错误只有一格 [error]**：搜索期失败（连不上 / 凭证过期 / ffmpeg 不可用）写在这里，
 * 常驻搜索页 QQ 分区；下载**动作**失败走 [QQDownloadState.actionMessage]（Snackbar）。
 * 两条渲染路径不能合并——共用一格会让「电脑没开」把曲库搜索结果盖掉（ADR-0006）。
 *
 * 防抖（300ms）与代次在 `QQDownloadViewModel` 里，本 slice 只装状态。
 */
data class QQSearchState(
    /** 查询词（输入框即时值，防抖前的原文）。 */
    val query: String = "",
    val isSearching: Boolean = false,
    val songs: List<QQSongDto> = emptyList(),
    val albums: List<QQAlbumDto> = emptyList(),
    /**
     * 最近一次成功的歌曲搜索是否有结果。
     *
     * 目前**只写不读**（合并搜索的判据改看 [songs] / [albums] 是否为空）：保留它是因为
     * 旧状态里就有，删字段不属于这次拆 slice 的范围。别拿它当渲染依据。
     */
    val searchHadResults: Boolean = false,
    /** 搜索期失败的一行小字（常驻分区，见类注释）；null = 没有错误。 */
    val error: String? = null,
) {
    /**
     * 换服务时整块作废（默认值即已清，新增字段自动跟上，
     * `QQDownloadStateClearedTest` 用反射钉死）。
     */
    fun cleared(): QQSearchState = QQSearchState()
}
