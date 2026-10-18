# 09 — 向 Jellyfin 上报播放行为

**What to build:** Pulsar 播放歌曲时，把播放行为上报给 Jellyfin，让服务端的 `UserData` 真正记录「播过什么、什么时候播的、播了几次」。补上之后，发现页的「最近播放 / 最常播放」才会有用户自己的数据，而不是只反映其他客户端的历史。

## 背景（为什么要这张票）

工单 02 实现了查询，参数完全正确（`Filters=IsPlayed` + `SortBy=DatePlayed/PlayCount`），但验证时只看"请求格式对不对"，没验证"数据是不是真的"。

全仓检索确认：**Pulsar 从未向 Jellyfin 上报任何播放行为**——没有 `PlaybackStart` / `PlaybackStopped` / `/Sessions/Playing` 的调用。`PlaybackService` 的播放监听只更新回放增益和诊断信息。

后果：Jellyfin 的 `IsPlayed` 只包含**其他客户端**播过的歌。用户全程用 Pulsar 听，这两块就是空的或严重滞后。

**Blocked by:** None — can start immediately

**Status:** ready-for-human（代码完成 + 573 项单测全绿；真机验收待做：听一首歌后核对 Jellyfin PlayCount 与「最近播放」）

## 要做的

- [x] 数据层新增两个上报能力（`JellyfinApi` / `JellyfinDataSource`，沿用现有 `Result` + `JellyfinError` 约定）：
  - **开始播放**：`POST /Sessions/Playing`，body 至少含 `ItemId`、`PositionTicks`
  - **停止播放**：`POST /Sessions/Playing/Stopped`，body 至少含 `ItemId`、`PositionTicks`
- [x] `PlaybackService` 接上钩子：媒体项**开始播放**时上报开始；**切歌前**和**播放服务销毁时**上报停止
- [x] 上报所需的 `serverUrl` / `userId` / `accessToken` 从已持久化的登录会话读取（`SessionRepository.load()`），不要新开一套存储
- [x] **上报失败必须静默**：网络错误、未登录、会话失效都只记日志，绝不影响播放、不弹错误、不拖慢切歌
- [x] **节流/去重**：同一首歌不重复上报开始；播放中的进度上报（若实现）频率有上限
- [x] **不污染离线流程**：离线时上报失败属于正常情况，不要触发 `goOffline()` 或任何状态机变化

## 验收标准

- [ ] 在 Pulsar 里完整听一首歌 → Jellyfin 侧该曲目 `PlayCount` +1、`LastPlayedDate` 更新
- [ ] 重新进入发现页 → 「最近播放」第一首就是刚听的歌
- [ ] 断网状态下播放 → 正常播放，仅日志提示上报失败
- [ ] 切歌不因上报变慢（主观 + 无 ANR）
- [ ] 未登录 / 会话过期时播放本缓存歌曲 → 上报静默跳过
- [x] 数据层测试：`MockEngine` 断言上报请求的 URL、方法、body 字段
- [x] 上报失败的路径有测试覆盖（失败不抛到调用方）

## 实现提示

- `PositionTicks` 是 Jellyfin 的时间单位：**1 ms = 10,000 ticks**，直接从播放器位置换算
- `ItemId` 从 `MediaItem.mediaId` 取（需确认播放队列里的 mediaId 就是 Jellyfin 曲目 id）
- 停止上报最好带**实际播放时长**，避免只点开 3 秒就被记成"听过"
- 可选但建议：实现 `POST /Sessions/Playing/Progress` 让服务端知道进度，这样「继续播放」位置也能跨设备同步——**若实现，纳入本票**

## Comments

- 这张票不在最初的 8 张票里，是验收发现的数据链路缺口。补完后 02 的成果才真正生效。
- 不要为了上报新增本地数据库；Jellyfin 的 `UserData` 就是真源。
