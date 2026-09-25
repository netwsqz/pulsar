# 05 — 设置页缓存区块

**What to build:** 设置页里看到「缓存占 3.2 GB / 共 88 首」，能一键清空。占了用户空间的东西，得让他看得见（ADR-0003 沿用了 ADR-0002 对回收站的要求）。

**Blocked by:** 01 — 音频缓存落地

**Status:** ready-for-human（代码完成）

- [x] 设置页加一块独立的缓存区块，**照 `RecycleBinSection` 的样子写**（独立 composable，别再往主 Column 里塞）
- [x] 显示当前占用（人类可读，照现有 `formatBytes`）和**存了多少首**（只算完整副本）
- [x] 「清空缓存」按钮 + 二次确认
- [x] 确认框文案必须说清「**歌还在电脑上**，清掉的只是手机上的副本」——别让用户以为在删东西（ADR-0003）
- [x] 清空期间要有忙碌状态，不能连点
- [x] 清空只动手机本地（**铁律 3**）：PC 服务没开、完全没网，这个操作照样成功
- [x] 测试：占用格式化的纯函数；ViewModel 层断言清空被调用、忙碌状态正确

## Comments

**实现记录（2026-09-25，agent）：**

- `MediaCacheSection` composable，结构照 `RecycleBinSection`；每次进设置页 `LaunchedEffect` 重拉占用。
- 清空走 `MediaCache.clear()`（SimpleCache 逐 key 删，不删目录——实例还活着）。
- `formatBytes` 复用回收站那一份，没新写。
