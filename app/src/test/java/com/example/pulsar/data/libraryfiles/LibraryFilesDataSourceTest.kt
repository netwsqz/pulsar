package com.example.pulsar.data.libraryfiles

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件操作数据层。这一层的每个方法都会**真的动用户电脑上的文件**，
 * 所以除了字段映射，重点验的是「请求真的带上了密钥」和「401 被单独认出来」——
 * 前者漏了整个删除接口在局域网里裸奔，后者漏了用户看不到「密钥不对」。
 */
class LibraryFilesDataSourceTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun clientWith(
        status: HttpStatusCode = HttpStatusCode.OK,
        content: String = "{}",
        capture: (HttpRequestData) -> Unit = {}
    ): HttpClient = HttpClient(MockEngine { request ->
        capture(request)
        respond(content = content, status = status, headers = jsonHeaders)
    })

    private fun dataSource(client: HttpClient, key: String = KEY) =
        LibraryFilesDataSource(client, BASE_URL, key)

    @Test
    fun `every request carries the file ops key header`() = runTest {
        val keys = mutableListOf<String?>()
        val client = clientWith { keys += it.headers["X-Pulsar-Key"] }
        val api = dataSource(client, key = "s3cret-key")

        api.delete(listOf("x"))
        api.rename("x", title = "y")
        api.recycle()
        api.restore(listOf("x"))
        api.purge()

        assertEquals(List(5) { "s3cret-key" }, keys)
        client.close()
    }

    @Test
    fun `each operation hits its own endpoint with the right method`() = runTest {
        val seen = mutableListOf<Pair<String, String>>()
        val client = clientWith { seen += it.method.value to it.url.encodedPath }
        val api = dataSource(client)

        api.delete(listOf("x"))
        api.rename("x", title = "y")
        api.recycle()
        api.restore(listOf("x"))
        api.purge()

        assertEquals(
            listOf(
                "POST" to "/api/library/delete",
                "POST" to "/api/library/rename",
                "GET" to "/api/library/recycle",
                "POST" to "/api/library/recycle/restore",
                "POST" to "/api/library/recycle/purge"
            ),
            seen
        )
        client.close()
    }

    @Test
    fun `requests go to the configured base url`() = runTest {
        var host: String? = null
        var port: Int? = null
        val client = clientWith { host = it.url.host; port = it.url.port }

        dataSource(client).recycle()

        assertEquals("192.168.1.10", host)
        assertEquals(8900, port)
        client.close()
    }

    @Test
    fun `delete posts the paths array as json`() = runTest {
        var body: String? = null
        var contentTypeHeader: String? = null
        val client = clientWith(content = """{"deleted":[],"failed":[]}""") { request ->
            body = (request.body as TextContent).text
            contentTypeHeader = request.body.contentType.toString()
        }

        dataSource(client).delete(listOf("Adele/21/01 Hello.flac", "周杰伦/叶惠美/03 晴天.flac"))

        assertEquals(
            """{"paths":["Adele/21/01 Hello.flac","周杰伦/叶惠美/03 晴天.flac"]}""",
            body
        )
        assertTrue(contentTypeHeader!!.startsWith("application/json"))
        client.close()
    }

    @Test
    fun `rename posts only the fields it was given`() = runTest {
        // 服务端只写传了的字段：多传一个空串会把用户的标签清掉。
        var body: String? = null
        val client = clientWith(content = """{"ok":true,"path":"p"}""") { request ->
            body = (request.body as TextContent).text
        }

        dataSource(client).rename("Adele/21/01 Hello.flac", title = "Hello (Live)")

        assertEquals("""{"path":"Adele/21/01 Hello.flac","title":"Hello (Live)"}""", body)
        client.close()
    }

    @Test
    fun `rename posts all three fields at once`() = runTest {
        var body: String? = null
        val client = clientWith(content = """{"ok":true,"path":"p"}""") { request ->
            body = (request.body as TextContent).text
        }

        dataSource(client).rename("p", title = "晴天", artist = "周杰伦", album = "叶惠美")

        assertEquals("""{"path":"p","title":"晴天","artist":"周杰伦","album":"叶惠美"}""", body)
        client.close()
    }

    @Test
    fun `rename clears a field when given an empty string`() = runTest {
        // 空串 ≠ 没传：显式传空串是要把那个标签清掉，不能当成「不动」给省掉。
        var body: String? = null
        val client = clientWith(content = """{"ok":true,"path":"p"}""") { request ->
            body = (request.body as TextContent).text
        }

        dataSource(client).rename("p", artist = "")

        assertEquals("""{"path":"p","artist":""}""", body)
        client.close()
    }

    @Test
    fun `delete parses failures alongside successes`() = runTest {
        // 服务端的 delete 是批量的：一个坏路径收进 failed，不拖垮整批。
        val client = clientWith(
            content = """{"deleted":["Adele/21/01 Hello.flac"],
                "failed":[{"path":"Bade/21/x.flac","error":"LibraryFilesError: 路径越出范围"}]}"""
        )

        val response = dataSource(client).delete(listOf("a", "b")).getOrThrow()

        assertEquals(listOf("Adele/21/01 Hello.flac"), response.deleted)
        assertEquals("Bade/21/x.flac", response.failed.single().path)
        assertTrue(response.failed.single().error.contains("越出范围"))
        client.close()
    }

    @Test
    fun `rename parses ok and the resulting path`() = runTest {
        val client = clientWith(content = """{"ok":true,"path":"周杰伦/叶惠美/03 晴天.flac"}""")

        val response = dataSource(client).rename("p", title = "t").getOrThrow()

        assertTrue(response.ok)
        assertEquals("周杰伦/叶惠美/03 晴天.flac", response.path)
        client.close()
    }

    @Test
    fun `recycle parses files with size and total size`() = runTest {
        val client = clientWith(
            content = """{"files":[{"rel_path":"Adele/21/01 Hello.flac","size":41000000,
                "deleted_at":1758000000.5}],"total_size":41000000}"""
        )

        val response = dataSource(client).recycle().getOrThrow()

        assertEquals("Adele/21/01 Hello.flac", response.files.single().relPath)
        assertEquals(41_000_000L, response.files.single().size)
        assertEquals(41_000_000L, response.totalSize)
        client.close()
    }

    @Test
    fun `empty recycle bin parses as empty`() = runTest {
        val client = clientWith(content = """{"files":[],"total_size":0}""")

        val response = dataSource(client).recycle().getOrThrow()

        assertTrue(response.files.isEmpty())
        assertEquals(0L, response.totalSize)
        client.close()
    }

    @Test
    fun `restore parses restored paths and refusals`() = runTest {
        val client = clientWith(
            content = """{"restored":["Adele/21/01 Hello.flac"],
                "failed":[{"path":"Adele/21/02 x.flac","error":"原位置已有同名文件，不覆盖"}]}"""
        )

        val response = dataSource(client).restore(listOf("a", "b")).getOrThrow()

        assertEquals(listOf("Adele/21/01 Hello.flac"), response.restored)
        assertTrue(response.failed.single().error.contains("不覆盖"))
        client.close()
    }

    @Test
    fun `purge reports how many files were really deleted`() = runTest {
        val client = clientWith(content = """{"purged":3}""")

        assertEquals(3, dataSource(client).purge().getOrThrow().purged)
        client.close()
    }

    @Test
    fun `http 401 maps to Unauthorized not Http`() = runTest {
        // 跟 Http(401) 分开：界面要能提示「密钥不对，去设置页检查」，而不是笼统的「服务出错」。
        val client = clientWith(
            status = HttpStatusCode.Unauthorized,
            content = """{"detail":"文件操作密钥不正确：请填 App 设置页里那把"}"""
        )

        val error = dataSource(client).recycle().exceptionOrThrow()

        assertTrue(error is LibraryFilesError.Unauthorized)
        assertTrue((error as LibraryFilesError.Unauthorized).detail.contains("密钥"))
        client.close()
    }

    @Test
    fun `http 400 maps to Http carrying the server detail`() = runTest {
        val client = clientWith(
            status = HttpStatusCode.BadRequest,
            content = """{"detail":"文件不存在: 'x.flac'"}"""
        )

        val error = dataSource(client).rename("x.flac", title = "y").exceptionOrThrow()

        assertTrue(error is LibraryFilesError.Http)
        assertEquals(400, (error as LibraryFilesError.Http).statusCode)
        assertTrue(error.detail.contains("文件不存在"))
        client.close()
    }

    @Test
    fun `connection failure maps to ServiceUnreachable`() = runTest {
        val client = HttpClient(MockEngine { throw java.io.IOException("connection refused") })

        val result = dataSource(client).delete(listOf("x"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrThrow() is LibraryFilesError.ServiceUnreachable)
        client.close()
    }

    @Test
    fun `unparseable body maps to InvalidResponse`() = runTest {
        val client = clientWith(content = "不是 JSON")

        val result = dataSource(client).recycle()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrThrow() is LibraryFilesError.InvalidResponse)
        client.close()
    }

    private fun <T> Result<T>.exceptionOrThrow(): Throwable =
        fold({ throw IllegalStateException("expected failure but succeeded") }) { it }

    private companion object {
        const val BASE_URL = "http://192.168.1.10:8900"
        const val KEY = "test-key"
    }
}
