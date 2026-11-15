package com.example.pulsar.session

import com.example.pulsar.AuthorizationSink
import com.example.pulsar.SessionRepository
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 会话凭据的唯一来源（候选 6 / 工单 #20、#21）。
 *
 * **#21 起已接线**：`JellyfinViewModel` 只从这个 module 取 `current` / `api`，
 * 登录 / 恢复 / 退出 / 过期都由它执行；内部暂时委托旧的 [SessionRepository] /
 * [JellyfinApiFactory]，Step 4 再把数据接口收成会话作用域接口并删掉旧模型。
 *
 * 三条约定：
 * - [current] 是会话真相；[api] 随它一起换，旧 API 不会留给在途协程之外的人。
 * - [authorizationHeader] 只给播放服务 / 下载上游用；**不要进 UI、不要打日志**。
 * - 登出只清会话；下载字节与名单不归这里管（ADR-0007）。
 *
 * [authorizationSink]（生产上是 `PulsarApplication`）也由本类统一写：
 * ViewModel 不再有第二个写 `activeAuthorization` 的地方；Step 3 再让播放服务直接读本类。
 */
class SessionManager(
    private val store: SessionRepository,
    private val apiFactory: JellyfinApiFactory,
    private val scope: CoroutineScope,
    private val authorizationSink: AuthorizationSink? = null,
) {
    private val _current = MutableStateFlow<Session?>(null)
    val current: StateFlow<Session?> = _current.asStateFlow()

    private val _api = MutableStateFlow<JellyfinApi?>(null)
    val api: StateFlow<JellyfinApi?> = _api.asStateFlow()

    private val _loginHint = MutableStateFlow<LoginHint?>(null)

    /**
     * 串行化「写存储 + 发布会话」。`login` 是先落盘再发布，若不加锁，`expire` 的异步清库
     * 可能正好落在两步之间，把刚保存的新会话擦掉（虽然 `_current` 还是 null）。
     */
    private val storeMutex = Mutex()

    /** 会话过期后保留给登录页的地址 / 用户名；登出时清空。 */
    val loginHint: StateFlow<LoginHint?> = _loginHint.asStateFlow()

    /** 播放服务在别的线程读它，按旧 `activeAuthorization` 的口径用 `@Volatile`。 */
    @Volatile
    private var header: String? = null

    /** 只给播放服务 / 下载上游用；不要进 UI、不要打日志。 */
    val authorizationHeader: String?
        get() = header

    /**
     * 恢复上次保存的会话。读失败、没有会话或建不出 API 时返回 false 且不发布任何状态；
     * 不在这里验证令牌（401 由调用方走 [expire]）。
     */
    suspend fun restore(): Boolean = storeMutex.withLock {
        val saved = try {
            store.load()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return@withLock false
        } ?: return@withLock false
        val api = try {
            apiFactory.create(saved.serverUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return@withLock false
        }
        publish(saved.toSession(), api)
        true
    }

    /** 登录成功后先落盘再发布；任何一步失败都不留下半截会话。 */
    suspend fun login(serverUrl: String, username: String, password: String): Result<Session> {
        // 与现有 ViewModel 一致：发起新登录立刻摘掉旧会话，失败也不回滚（旧存储保持原样）。
        clearSession()
        val api = try {
            apiFactory.create(serverUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Result.failure(error)
        }
        val signedIn = api.login(username, password).getOrElse { return Result.failure(it) }
        val session = Session(serverUrl, signedIn.userId, username, signedIn.accessToken)
        return storeMutex.withLock {
            try {
                store.save(session.toSavedSession())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return@withLock Result.failure(error)
            }
            publish(session, api)
            Result.success(session)
        }
    }

    /** 主动退出：清内存会话与存储，登录页预填也一并清掉；下载不动（ADR-0007）。 */
    suspend fun signOut() {
        storeMutex.withLock {
            _loginHint.value = null
            clearSession()
            store.clear()
        }
    }

    /**
     * 只撤内存里的会话 / API / 鉴权头，**保留存储**。
     *
     * 给「恢复出了会话、但校验请求失败（如断网且没有快照）」用：界面回到登录表单，
     * 下次启动仍能恢复这份可用会话；这与 [expire] 的清存储语义不同。
     */
    fun clearInMemory() {
        clearSession()
    }

    /**
     * 会话过期：清内存会话与存储，但把服务地址 / 用户名留给登录页预填。
     *
     * 清存储是异步的（规格要求本方法是普通函数）。`login` 的「落盘 + 发布」在同一把
     * [storeMutex] 内完成，所以这里要么排在新会话落盘之前清，要么看到 `current` 已非空
     * 而跳过——不会把新一轮会话擦掉。
     */
    fun expire() {
        // 只在确实有会话时更新预填：多个在途请求同时报 401 时，重复 expire 不能把
        // 上一次留下的地址 / 用户名冲掉。
        _current.value?.let { _loginHint.value = LoginHint(it.serverUrl, it.username) }
        clearSession()
        scope.launch {
            storeMutex.withLock {
                if (_current.value == null) store.clear()
            }
        }
    }

    private fun publish(session: Session, api: JellyfinApi) {
        // 先放 API 再放会话：观察 current 的一侧拿到的 api 一定属于这个会话。
        _api.value = api
        val value = api.authorizationHeader(session.accessToken)
        header = value
        authorizationSink?.activeAuthorization = value
        _current.value = session
    }

    private fun clearSession() {
        // 与 publish 相反：先撤鉴权头与 API，最后撤会话，读方不会在 current 还有值时
        // 拿到空 header。
        header = null
        authorizationSink?.activeAuthorization = null
        _api.value = null
        _current.value = null
    }
}
