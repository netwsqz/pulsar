# ADR-0011：会话凭据由 SessionManager 单一持有，数据接口按会话作用域创建

**状态：** 已接受（2026-11-15）

**相关：** [ADR-0007](0007-downloads-replace-auto-cache.md)（登出不清下载）、`docs/session-scope-refactor.md`（候选 6，工单 #20–#23）

## 背景

会话凭据曾经有三条通道：

1. `PulsarApplication.activeAuthorization`：`@Volatile` 全局鉴权头，7 处 ViewModel 写入，
   播放服务与下载上游各自读取；
2. `SessionRepository` / `SavedSession`：DataStore 里的会话；
3. `JellyfinSession(userId, accessToken)`：数据层登录返回值。

同时 `JellyfinApi` 的 12 个数据方法都要求调用方逐次传 `userId + accessToken`，每个调用点
都在搬凭据；鉴权头字符串在 `JellyfinUrlBuilder` 和 `JellyfinDataSource` 各拼一份。
`PlaybackReporter` 每次上报还重新 `apiFactory.create(serverUrl)`，重读安装 ID。

风险很直接：凭据有第二份缓存就有「旧令牌 + 新 API」的拼接窗口；签名里带着 token 就容易
某天被写进 URL 或日志；两份头格式一旦漂移，会出现一半请求成功一半 401。

## 决定

**1. `SessionManager` 是会话凭据的唯一持有者。**

- `current: StateFlow<Session?>` / `api: StateFlow<SessionScopedJellyfinApi?>`；
- `authorizationHeader` 只给播放服务 / 下载上游现读，**不进 UI、不打日志**；
- `currentConnection()` 原子快照，内部读方不得分别读 `current` / `api` 再拼；
- 登录、恢复、登出、过期全部经 manager；**删除全局 `activeAuthorization` 缓存**（#22）。

**2. 会话模型只有一个 `Session(serverUrl, userId, username, accessToken)`。**

`SavedSession` 与 `JellyfinSession` 删除。DataStore 的键名与字段名保持不变
（`session_server_url` 等），升级不丢会话。`Session.toString()` 对令牌打码。

**3. 数据接口按会话作用域创建，方法不再收凭据。**

- `SessionScopedJellyfinApi`：创建时绑定 `Session`，`getAlbums()` / `search(query)` /
  `streamUrl(mediaId)` 只收领域参数；
- `JellyfinLoginApi`：未登录时唯一入口，`login(username, password)` 返回完整 `Session`；
- `JellyfinApiFactory.createLoginApi(serverUrl)` / `create(session)` 分两步建；
- 换账号就换 API 实例，旧实例只可能被在途协程持有，由登录代次挡掉。

**4. 鉴权头只有一个拼装实现。**

`mediaBrowserAuthorization(deviceId, accessToken?)` 是唯一出处；URL 构建器、数据源、
登录请求共用。`accessToken = null` 仅用于登录。

## 理由

- **单一持有消除拼接窗口。** 会话与 API 一起发布、一起撤销；播放服务每次建数据源现读
  header，重新登录不用重建播放器。
- **签名收窄是安全边界。** 调用方拿不到 token，就不会把它放进 URL / 日志；数据源内部
  仍只把 token 放进 `Authorization` 头。
- **一份头格式。** 以前两处字符串必须人工同步；共享纯函数后改 Client / Device / Version
  只改一处，测试用「数据源头 == URL 构建器头」钉住。
- **存储兼容优先。** 只改 Kotlin 类型名，不改 DataStore 键；`serverUrl` 保留用户输入原样
  （账号作用域用于下载名单，规范化会改变作用域字符串）。

## 后果

- `JellyfinApi` 旧接口与 `SavedSession` / `JellyfinSession` 已删除；Fake 实现从「18 个方法
  都带凭据」变成只实现会话作用域接口。
- `PlaybackReporter` 通过 `SessionPlaybackReportSource` 复用登录时建好的 API，不再每次重建
  数据源 / 重读安装 ID。
- `LibraryCache.Connection` 只持有 `SessionScopedJellyfinApi`，加载编排不再接触会话字段。
- 新增数据方法时：加到 `SessionScopedJellyfinApi`，在 `JellyfinDataSource` 用绑定的
  `session` 实现；**不要再把 `userId` / `accessToken` 加回参数**。
- 风险：登录时先建登录 API、成功后再建会话作用域 API，会多构造一次数据源（共享
  `HttpClient`，代价是一次 URL builder / 安装 ID 读取）。真机验收见
  `docs/session-scope-refactor.md` §8。
