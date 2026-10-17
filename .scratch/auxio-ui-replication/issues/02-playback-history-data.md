# 02 — 播放历史数据层

**What to build:** 打通「最近播放 / 最常播放 / 最近添加」的完整数据链——从 Jellyfin 服务端查询，到 ViewModel 暴露给 UI 的状态字段。这是发现页个人化聚合的数据基础。

Pulsar 现在的数据层**完全没有**播放历史查询（`SortBy` 只有 `SortName`/`Random`），这一票是纯新增。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] `JellyfinApi` / `JellyfinDataSource` 新增查询：最近播放、最常播放、最近添加（Jellyfin 原生支持对应排序参数）
- [ ] 请求参数正确（排序字段符合语义），错误映射沿用现有 `JellyfinError`
- [ ] `JellyfinUiState` 新增对应字段，`JellyfinViewModel` 新增对应 `loadXxx()`，沿用现有「字段非空即已加载」幂等模式 + generation 防过期
- [ ] 会话切换/过期时这些新字段一并清空（不残留上个账号数据）
- [ ] 单个查询失败不影响其余（发现页可降级）
- [ ] 数据层测试：`MockEngine` 断言请求参数 + DTO 映射
- [ ] ViewModel 测试：`FakeJellyfinApi` 覆盖幂等、防过期、失败降级、切号清空
