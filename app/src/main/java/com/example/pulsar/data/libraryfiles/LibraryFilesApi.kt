package com.example.pulsar.data.libraryfiles

/**
 * 曲库文件操作（PC 伴生服务 /api/library/ endpoints）的客户端接口。
 *
 * 五个方法都**会改动用户电脑上的文件**，全部需要文件操作密钥；失败统一映射为
 * [LibraryFilesError]。服务地址与密钥由调用方提供并持久化（见 QQDownloadSettings）。
 */
interface LibraryFilesApi {
    /**
     * 删除（**实际是挪进回收站**，可恢复）。
     * 逐个处理：坏路径收进 [LibraryDeleteResponse.failed]，不拖垮整批。
     */
    suspend fun delete(paths: List<String>): Result<LibraryDeleteResponse>

    /**
     * 改文件**内嵌标签**（不是 Jellyfin 元数据，见 ADR-0001）。
     * 只传要改的字段，null = 不动；空串 = 清空该字段。
     */
    suspend fun rename(
        path: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null
    ): Result<LibraryRenameResponse>

    /** 回收站内容与总占用；按删除时间倒序。 */
    suspend fun recycle(): Result<LibraryRecycleResponse>

    /** 从回收站挪回原处；目标位置已有同名文件时拒绝，不覆盖。 */
    suspend fun restore(paths: List<String>): Result<LibraryRestoreResponse>

    /** **真删**回收站里的东西（全功能唯一不可逆的操作），返回删掉的文件数。 */
    suspend fun purge(): Result<LibraryPurgeResponse>
}
