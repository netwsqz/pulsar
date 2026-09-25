package com.example.pulsar.data.libraryfiles

/**
 * 曲库文件操作的错误类型。
 *
 * 跟 QQDownloadError 分开：那一组是下载链路的（503 = 凭证过期），
 * 这一组动的是**用户电脑上的真文件**，401 有专门含义（文件操作密钥不对）。
 *
 * - [ServiceUnreachable] 连不上（服务没启动 / 地址错 / 不在同一网络）
 * - [Unauthorized] HTTP 401，密钥没填或填错——界面要专门提示去设置页改
 * - [Http] 其它非 2xx（400 参数/路径被拒、500 服务端出错）
 * - [InvalidResponse] 连上了但返回的东西看不懂
 */
sealed class LibraryFilesError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ServiceUnreachable(cause: Throwable) : LibraryFilesError("无法连接电脑端服务", cause)
    class Unauthorized(val detail: String) : LibraryFilesError(detail)
    class Http(val statusCode: Int, val detail: String) :
        LibraryFilesError("服务返回 HTTP $statusCode: $detail")
    class InvalidResponse(cause: Throwable) : LibraryFilesError("服务返回了无法识别的数据", cause)
}
