package com.example.pulsar.data.qqdownload

/**
 * QQ 音乐下载服务的错误类型。
 *
 * 服务是局域网内自建的（E:\nsmic\server），错误语义与 Jellyfin 不同：
 * - [ServiceUnreachable] 连不上（服务没启动 / 地址错 / 不在同一网络）
 * - [CredentialExpired] 凭证过期（HTTP 503，需要用户在电脑上重提凭证）
 * - [Http] 其它非 2xx（含 400 参数错、404 任务不存在、504 专辑拉取失败）
 */
sealed class QQDownloadError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ServiceUnreachable(cause: Throwable) : QQDownloadError("无法连接下载服务", cause)
    class CredentialExpired(val detail: String) : QQDownloadError(detail)
    class Http(val statusCode: Int, val detail: String) : QQDownloadError("下载服务返回 HTTP $statusCode: $detail")
    class InvalidResponse(cause: Throwable) : QQDownloadError("下载服务返回了无法识别的数据", cause)
}
