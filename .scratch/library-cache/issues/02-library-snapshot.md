# 02 — 曲库快照

**What to build:** 在线逛一遍曲库，断网重开 App，专辑和全部音乐**还在**。这一票做完就能亲眼看到东西了——不需要任何音频缓存。

**Blocked by:** None — can start immediately.

**Status:** ready-for-human（代码完成）

- [x] 新接口 `LibrarySnapshotRepository`（照项目已有的 `SessionRepository`（接口）/ `SessionStore`（实现）那套写），通过 `JellyfinViewModelFactory` 注入
- [x] 文件实现：一份 JSON 落在 App 私有目录，内容是曲库那几屏要的专辑与曲目（歌名、专辑、时长、封面标识、**回放增益**）
- [x] **在线请求成功时覆写快照；请求失败时读快照顶上**（**铁律 4**：快照只在线下兜底，不在线上抢活）
- [x] 覆盖**发现 / 全部音乐 / 专辑**三屏
- [x] **播放列表不进快照**——不要为了「离线也能看播放列表」去提前拉所有列表
- [x] **不需要**往快照里塞文件大小之类的字段：「完整副本」的判据走 Media3 自带的缓存元数据，跟快照无关
- [x] 退出登录时**清掉快照**（跟现有「换账号清空所有库缓存」的约定一致）
- [x] 快照文件坏了 / 不存在 / 读不出来 → 当「没有快照」，**不影响在线使用**（同样不能变成新的故障源）
- [x] 测试：序列化 / 反序列化的纯函数；ViewModel 层用现有 Fake 测「在线成功覆写」「请求失败读快照」「退出登录清空」

## Comments

**实现记录（2026-09-25，agent）：**

- `LibrarySnapshot.kt`：`LibrarySnapshot`（@Serializable，Track/Album 直接复用域模型）+ `LibrarySnapshotRepository` 接口 + `LibrarySnapshotCodec` 纯函数 + `FileLibrarySnapshotRepository`（临时文件改名原子写）。
- 写盘先写 `.tmp` 再 rename：中途被杀时读到的要么旧要么新，不会读到半截 JSON。
- 专辑详情页离线兜底用「按专辑名从全部曲目里挑」——快照不存专辑→曲目索引，避免存两份几乎重复的曲目表。代价：同名专辑（不同歌手）混在一起，但离线时没缓存的歌照样标灰，可接受。
- 坏文件/不存在 → `decode` 返回 null，`LibrarySnapshotTest` 钉死。
