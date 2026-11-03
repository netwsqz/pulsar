package com.example.pulsar

import com.example.pulsar.domain.model.Album

/**
 * 艺术家组详情头部的摘要（工单 11）。和专辑/播放列表的 [albumSummary] / [playlistSummary]
 * 是同一套头部语言，只是计量单位换成「张」——这一页列的本来就是专辑。
 *
 * 计数必须和下方网格实际画出的卡片数**同源**：[albums] 是界面真正要渲染的那一份（离线时
 * 已经滤过，只剩有下载的）。离线视图写成「已下载 N 张 · 共 M 张」，M 取服务端总数但不小于
 * 可见张数——服务端计数滞后时不能出现「已下载 3 张 · 共 1 张」这种对不上的摘要。
 *
 * [serverTotal] 传 null 表示「不知道服务端一共多少张」（艺术家不在快照里、或还在加载），
 * 此时总数取可见张数。离线分支直接复用 [downloadedTrackSummary]（单位「张」），不另写一份。
 */
internal fun artistGroupSummary(
    albums: List<Album>,
    isOfflineView: Boolean,
    serverTotal: Int? = null
): String {
    val visible = albums.size
    if (!isOfflineView) return "$visible 张专辑"
    // 离线摘要复用曲目那份的骨架，只是单位换成「张」。
    return downloadedTrackSummary(visible, serverTotal, unit = "张")
}
