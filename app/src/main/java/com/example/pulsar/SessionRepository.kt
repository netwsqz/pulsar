package com.example.pulsar

import com.example.pulsar.download.AccountScope
import com.example.pulsar.session.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface SessionRepository {
    suspend fun load(): Session?
    suspend fun save(session: Session)
    suspend fun clear()

    /** 凭据失效不等于主动退出；仍可用旧归属查本机下载。 */
    suspend fun clearExpired() = clear()

    /** 生产存储持续观察；非持久化实现默认只给当前快照。 */
    fun downloadAccounts(): Flow<AccountScope?> = flow {
        emit(load()?.let { AccountScope(it.serverUrl, it.userId) })
    }
}
