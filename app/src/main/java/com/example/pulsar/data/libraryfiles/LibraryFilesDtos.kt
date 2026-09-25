package com.example.pulsar.data.libraryfiles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 批量接口（删除 / 恢复）共用的请求体：一串相对曲库根的路径。 */
@Serializable
data class LibraryPathsRequest(val paths: List<String>)

/**
 * 改名请求。三个标签字段都可选，**只写传了的**——服务端把 null 当「这个字段不动」。
 * 空串不是 null：显式传空串是要把那个标签清掉。
 */
@Serializable
data class LibraryRenameRequest(
    val path: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null
)

/** 批量操作里单个失败项。服务端一个坏路径收进这里，不拖垮整批。 */
@Serializable
data class LibraryFailedPath(val path: String = "", val error: String = "")

@Serializable
data class LibraryDeleteResponse(
    val deleted: List<String> = emptyList(),
    val failed: List<LibraryFailedPath> = emptyList()
)

@Serializable
data class LibraryRenameResponse(val ok: Boolean = false, val path: String = "")

/** 回收站里的一个文件。[relPath] 是相对曲库根的路径，恢复时原样传回去。 */
@Serializable
data class LibraryRecycleFile(
    @SerialName("rel_path") val relPath: String = "",
    val size: Long = 0,
    /** 删除时间（Unix 秒，服务端写的）。 */
    @SerialName("deleted_at") val deletedAt: Double = 0.0
)

@Serializable
data class LibraryRecycleResponse(
    val files: List<LibraryRecycleFile> = emptyList(),
    @SerialName("total_size") val totalSize: Long = 0
)

@Serializable
data class LibraryRestoreResponse(
    val restored: List<String> = emptyList(),
    val failed: List<LibraryFailedPath> = emptyList()
)

/** 清空回收站：唯一不可逆的操作，[purged] 是真删掉的文件数。 */
@Serializable
data class LibraryPurgeResponse(val purged: Int = 0)
