# 03 — App：文件操作数据层

**What to build:** Android 侧访问这几个新接口的数据层。照 `data/qqdownload/` 的分文件方式和错误映射写。

**Blocked by:** 02

**Status:** resolved

- [x] 新包 `app/src/main/java/com/example/pulsar/data/libraryfiles/`，分文件照 `data/qqdownload/` 的来
- [x] `LibraryFilesApi`（接口）+ `LibraryFilesDataSource`（Ktor 实现），方法对应 02 的五个接口，**全部返回 `Result<T>`**
- [x] `LibraryFilesError` sealed class：`ServiceUnreachable` / `Unauthorized`（密钥错或被拒）/ `Http(statusCode, detail)` / `InvalidResponse`
- [x] **`Unauthorized` 要跟 `Http(401)` 分开**——UI 要能提示"密钥不对"，而不是笼统的"服务出错"
- [x] `CancellationException` 一律重新抛出（照 `JellyfinDataSource` 现有约定）
- [x] **服务地址与密钥复用 `QQDownloadPreferences`**——是同一个服务，不该让用户在两处配同一个东西
- [x] `QQDownloadPreferences` 加一个密钥字段（默认空串）
- [x] 发送时带上 header `X-Pulsar-Key`
- [x] 测试（`MockEngine`）：401 → `Unauthorized`、URL 拼接正确、请求体 JSON 形状正确、网络异常 → `ServiceUnreachable`、**断言请求头带上了密钥**

## Comments
