# SessionScope 重构执行计划

> 状态：**待研究**。本文是 candidate 6（会话凭据收成一个 SessionScope）的执行规格，不是 ADR。
> 相关：`docs/download-library-refactor.md`（AccountScope）、`docs/library-state-refactor.md`（SessionState）。
> 安全红线（CLAUDE.md）：**token 绝不出现在 URL、日志或 UI**；请求头用 MediaBrowser 格式；退出登录/会话过期时清库缓存与播放队列。

---

## 1. 目标

让「当前会话」只有一个来源：

- **一个 module**：`SessionManager`，拥有 `serverUrl / userId / token`、鉴权头、会话作用域 API。
- **一个 interface**：`current: StateFlow<Session?>`、`api: SessionScopedJellyfinApi?`、`authorizationHeader: String?`、`login() / signOut() / expire()`。
- **API 方法不再搬凭据**：`getAlbums()`、`search(query)`、`streamUrl(mediaId)`，不再每个方法带 `userId + accessToken`。
- **三条鉴权通道收成一条**：`activeAuthorization`、`SessionRepository`、`JellyfinSession` 统一。

## 2. 非目标

- 不改登录页的视觉与表单行为。
- 不改 Jellyfin 服务端接口、URL 格式、MediaBrowser 头格式。
- 不改离线探活、播放上报的语义（只改取凭据的来源）。
- 不引入 DI 框架、不引入加密存储（沿用 DataStore）。
- 不把密码存进 DataStore（现在也只存 token）。
- 不改 QQ 下载的独立服务地址/密钥。

## 3. 现状证据

| 位置 | 现状 |
|---|---|
| `JellyfinApi.kt:5–32` | 18 个方法；12 个数据方法带 `(userId, accessToken)` |
| `JellyfinDataSource.kt:24` | HTTP adapter + 鉴权头拼装 |
| `JellyfinDataSource.kt:354–356` | `mediaBrowserAuthorization` 一份 |
| `JellyfinUrlBuilder.kt:58–62` | 同样的 Client/Device/DeviceId/Version 字符串又一份 |
| `JellyfinDataSource.kt:359` | `JellyfinSession(userId, accessToken)` |
| `SessionStore.kt:14` | `SavedSession(serverUrl, userId, username, accessToken)` |
| `PulsarApplication.kt:35` | `activeAuthorization` `@Volatile`，仅内存 |
| `JellyfinViewModel.kt:143/186/235/247/281/319/1614` | 7 处写 `activeAuthorization` |
| `PlaybackService.kt:589` | `AuthHttpDataSourceFactory` 读 `activeAuthorization` |
| `PulsarApplication.kt:109` | 下载上游也读 `activeAuthorization` |
| `PlaybackReporter.kt:85` | 每次上报 `apiFactory.create(serverUrl)`，重新建 DataSource + 读安装 ID |
| `PlaybackReporterTest.kt:173–208` | Fake 为实现 2 个方法要实现 18 个方法 |
| `JellyfinViewModelTest.kt:2248–2323` | `FakeJellyfinApi` 18 个方法 |
| `PulsarApplication.kt:70–76` | 每次 `create` 都读一次安装 ID（DataStore） |

## 4. 默认决策

| 编号 | 决策 | 默认 |
|---|---|---|
| D1 | module 形态 | `SessionManager` 有状态 class；`SessionScopedJellyfinApi` 是接口 |
| D2 | 会话模型 | 统一成 `Session(serverUrl, userId, username, accessToken)`；`SavedSession` / `JellyfinSession` 合并 |
| D3 | API 作用域 | `JellyfinApiFactory.create(serverUrl, session)` 返回会话作用域 API；方法只收领域参数 |
| D4 | 鉴权头 | `SessionManager.authorizationHeader` 是唯一来源；`AuthorizationSink` 保留为播放服务的适配接口 |
| D5 | 播放上报 | `PlaybackReporter` 注入 `SessionManager` + `SessionScopedJellyfinApi`，不再每次建 DataSource |
| D6 | 过期 | 401 → `SessionManager.expire()`；VM 只负责显示「登录已失效」并清界面状态 |
| D7 | 存储 | 仍只存 token；不存密码；DataStore 键不变，升级不丢会话 |
| D8 | 迁移 | 先加新 module + 新 API，旧 API 保留；逐层切换；最后删旧签名 |

## 5. 目标形状（sketch）

```kotlin
data class Session(
    val serverUrl: String,
    val userId: String,
    val username: String,
    val accessToken: String,
)

class SessionManager(
    private val store: SessionRepository,
    private val apiFactory: SessionScopedJellyfinApiFactory,
    private val scope: CoroutineScope,
) {
    val current: StateFlow<Session?>
    val api: StateFlow<SessionScopedJellyfinApi?>

    /** 只给播放服务/下载上游用；不要进 UI、不要打日志。 */
    val authorizationHeader: String?

    suspend fun restore(): Boolean
    suspend fun login(serverUrl: String, username: String, password: String): Result<Session>
    suspend fun signOut()
    fun expire()
}
```

```kotlin
interface SessionScopedJellyfinApi {
    suspend fun getMusicItems(): Result<List<Track>>
    suspend fun getAlbums(): Result<List<Album>>
    suspend fun getRandomAlbums(limit: Int = 12): Result<List<Album>>
    suspend fun getRecentlyPlayed(limit: Int = 20): Result<List<Track>>
    suspend fun getMostPlayed(limit: Int = 20): Result<List<Track>>
    suspend fun getRecentlyAdded(limit: Int = 12): Result<List<Album>>
    suspend fun getAlbumTracks(albumId: String): Result<List<Track>>
    suspend fun getPlaylists(): Result<List<Playlist>>
    suspend fun getPlaylistTracks(playlistId: String): Result<List<Track>>
    suspend fun search(query: String): Result<SearchResults>
    suspend fun getLyrics(trackId: String): Result<Lyrics>
    suspend fun reportPlaybackStarted(itemId: String, positionTicks: Long): Result<Unit>
    suspend fun reportPlaybackStopped(itemId: String, positionTicks: Long): Result<Unit>
    fun streamUrl(mediaId: String): String
    fun imageRequest(itemId: String, imageTag: String?): JellyfinRequest
    fun authorizationHeader(): String
}
```

```kotlin
fun interface SessionScopedJellyfinApiFactory {
    suspend fun create(session: Session): SessionScopedJellyfinApi
}
```

设计要点：
- `SessionManager` 是唯一写 `activeAuthorization` 的地方（或者干脆删掉全局，改由 `AuthorizationSink` 从 manager 读）。
- `SessionManager.api` 在 `current` 变化时重建；`PlaybackReporter` 通过 `sessionManager.api.value` 取。
- `SessionScopedJellyfinApi` 的 adapter 内部仍用 `JellyfinUrlBuilder` + token；token 不进 URL（沿用现有约定）。
- `SessionManager` 不碰 UI 文案；错误映射仍由 `JellyfinError` + VM 的 `friendlyError` 负责。

## 6. 执行步骤

### Step 0 — 基线 + 调用点清单

1. 跑全量单测。
2. 列出所有 `userId` / `accessToken` 的调用点（grep）。
3. 列出所有 `activeAuthorization` 的读写点。
4. 列出 `SavedSession` / `JellyfinSession` 的构造/复制点。
5. 确认 `AuthorizationSink` 的实现与消费方（播放服务、下载）。

**验收**：知道 12 个 API 方法、7 个 `activeAuthorization` 写点、2 个会话模型的所有位置。

### Step 1 — 引入 `Session` + `SessionManager`（不改调用方）

1. 新增 `app/src/main/java/com/example/pulsar/session/Session.kt`、`SessionManager.kt`。
2. `SessionManager.restore/login/signOut/expire` 内部暂时调用旧 `SessionRepository` / `JellyfinApiFactory`。
3. 测试：`SessionManagerTest`：
   - restore 成功 / 无会话 / 读失败；
   - login 成功写入 store + 更新 current；
   - signOut 清 current + store，但不清下载（ADR-0007）；
   - expire 清 current + store，保留 serverUrl/username 给登录页；
   - `authorizationHeader` 随 current 变化。
4. 不接线；旧代码不动。

**验收**：新测试全绿；生产行为零变化。

### Step 2 — ViewModel 走 SessionManager

1. `JellyfinViewModel` 的 `session` / `dataSource` 字段替换成 `sessionManager.current` / `api`。
2. 删除 7 处 `authorizationSink.activeAuthorization = ...` 写入；由 manager 统一写。
3. `login()` 仍保留表单状态与代次，但调用 `sessionManager.login(...)`。
4. `signOut()` / `expireSession()` 调 manager，再清 UI state。
5. 现有 `JellyfinViewModelTest` 用 FakeSessionRepository 的地方改成 FakeSessionManager（或保留兼容构造）。

**验收**：登录 / 恢复 / 退出 / 过期四条路径测试全绿；真机走一遍。

### Step 3 — 播放服务 / 下载 / 上报走同一来源

1. `AuthHttpDataSourceFactory` 改成读 `SessionManager.authorizationHeader`（通过 `AuthorizationSink` 适配或直接注入 provider）。
2. `PulsarApplication.mediaDownloads()` 的上游源改读同一个 provider。
3. `PlaybackReporter` 注入 `SessionManager` + `SessionScopedJellyfinApi`；删除每次 `apiFactory.create`。
4. `PlaybackReporterTest` 的 Fake 从 18 个方法缩到 reporter 真正用的 2 个。
5. 删除 `activeAuthorization` 的全局写点（如果 `AuthorizationSink` 还需要，保留只读适配）。

**验收**：`grep activeAuthorization` 只剩 `SessionManager` 与 `AuthorizationSink` 适配；播放/下载/上报正常。

### Step 4 — 会话作用域 API + 签名收窄

1. 新增 `SessionScopedJellyfinDataSource`（或让 `JellyfinDataSource` 实现新接口）。
2. `JellyfinApiFactory.create(serverUrl, session)` 返回新接口。
3. 把 12 个方法的 `(userId, accessToken)` 参数删掉；更新所有调用点与 Fake。
4. 删除 `SavedSession` / `JellyfinSession`，统一用 `Session`；`SessionStore` 的序列化保持兼容（字段名不变）。
5. 合并鉴权头拼装：`JellyfinUrlBuilder` 与 `JellyfinDataSource` 共用一份 `MediaBrowserAuthorization` 纯函数。

**验收**：`JellyfinApi` 不再有 `userId/accessToken` 参数；`PlaybackReporter` 的 Fake 只有 2 个方法；测试全绿。

### Step 5 — 清理 + 文档

1. 删除旧的 `JellyfinApi` 或标 `@Deprecated`（如果 QQ 下载/其他模块还用）。
2. 更新 `CLAUDE.md` 的会话/鉴权章节：加 `SessionManager` 的唯一来源地位。
3. 更新 `CONTEXT.md`：加 **会话作用域（SessionScope）** 术语；说明 token 不进 URL/日志/UI。
4. 全量测试 + `assembleDebug` + 真机清单。
5. 如果决定删掉 `activeAuthorization` 全局，补一条 ADR：**会话凭据由 SessionManager 单一持有**。

## 7. 测试策略

- `SessionManagerTest`：restore / login / signOut / expire / header / store 失败。
- `SessionScopedJellyfinDataSourceTest`：沿用现有 MockEngine 测试，断言 URL 不含 token、header 含 token。
- `PlaybackReporterTest`：Fake 缩窄后仍覆盖开始/停止/去重/失败静默。
- `JellyfinViewModelTest`：登录/恢复/过期路径保留。
- 不新增 Compose 测试。

## 8. 真机验收清单

- [ ] 首次登录 → 曲库加载 → 播放
- [ ] 杀进程重进 → 恢复会话，不闪登录页
- [ ] 退出登录 → 回登录页，地址历史还在，下载不丢
- [ ] token 过期（服务端撤销）→ 提示重新登录，库缓存清空，播放队列清空
- [ ] 播放流带鉴权头，URL 里没有 token
- [ ] 封面图带鉴权头
- [ ] 播放上报成功（发现页最近播放更新）
- [ ] 下载带鉴权头，URL 里没有 token
- [ ] 换账号 → 上一个账号的曲库/下载可见性不串

## 9. 风险与待确认

| 风险 | 缓解 |
|---|---|
| token 生命周期变更导致播放中断 | `AuthorizationSink` 保留为适配层；播放服务每次创建数据源时现读 |
| 换账号时旧 API 仍被在途协程持有 | 沿用 loginGeneration / 会话代次；`SessionManager.current` 变化时取消旧请求 |
| `SavedSession` 字段改名导致升级丢会话 | DataStore 键与序列化字段保持不变；只改 Kotlin 类型名 |
| 两个 API 接口并存期混乱 | 新接口命名清晰（`SessionScopedJellyfinApi`）；旧接口只给未迁移模块 |
| 播放上报重建 DataSource 的性能问题 | 改成复用 `SessionManager.api`；安装 ID 只读一次 |

**待确认**
1. `SessionManager` 是否拥有登录流程，还是只拥有凭据？推荐拥有凭据 + 登录 API，表单状态留 VM。
2. `activeAuthorization` 是删除还是保留为 `SessionManager` 的派生缓存？推荐保留 `AuthorizationSink` 适配，删掉其他写点。
3. `JellyfinApi` 是改名还是新增接口？推荐新增 `SessionScopedJellyfinApi`，旧接口逐步废弃。
4. 是否把 `SessionRepository` 也并进 `SessionManager`？推荐 manager 组合它，不合并存储接口。

## 10. 新窗口开工提示词

```
按 docs/session-scope-refactor.md 执行。先做 Step 0 调用点清单，再做 Step 1：
新增 session/Session.kt 与 session/SessionManager.kt，内部先委托旧 SessionRepository /
JellyfinApiFactory，写 SessionManagerTest（restore / login / signOut / expire / header）。
不接线，生产行为不变。完成后跑全量单测 + assembleDebug，报告调用点清单与测试结果。
```
