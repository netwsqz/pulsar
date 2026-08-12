package com.example.pulsar.data.jellyfin

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** Builds Jellyfin request URLs while keeping credentials out of URLs. */
class JellyfinUrlBuilder(baseUrl: String) {
    private val normalizedBaseUrl: String = normalizeBaseUrl(baseUrl)

    fun loginUrl(): String {
        return appendPath("Users", "AuthenticateByName")
    }

    fun itemsUrl(userId: String): String {
        return appendPath("Users", pathSegment(userId), "Items")
    }

    fun mediaStreamRequest(mediaId: String, token: String, deviceId: String): JellyfinRequest {
        require(token.isNotBlank()) { "Token must not be blank" }
        require(deviceId.isNotBlank()) { "Device ID must not be blank" }
        val url = appendPath("Videos", pathSegment(mediaId), "stream") + "?static=true"
        return JellyfinRequest(
            url = url,
            headers = headersWithToken(deviceId, token)
        )
    }

    fun imageRequest(itemId: String, imageTag: String?, token: String, deviceId: String): JellyfinRequest {
        require(itemId.isNotBlank()) { "Item ID must not be blank" }
        require(token.isNotBlank()) { "Token must not be blank" }
        require(deviceId.isNotBlank()) { "Device ID must not be blank" }
        val base = appendPath("Items", pathSegment(itemId), "Images", "Primary")
        val url = if (imageTag != null) "$base?tag=${pathSegment(imageTag)}" else base
        return JellyfinRequest(
            url = url,
            headers = headersWithToken(deviceId, token)
        )
    }

    private fun headersWithToken(deviceId: String, token: String): Map<String, String> = mapOf(
        "Authorization" to
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", DeviceId=\"$deviceId\", " +
            "Version=\"0.1.0\", Token=\"$token\""
    )

    private fun appendPath(vararg segments: String): String {
        return buildString {
            append(normalizedBaseUrl)
            segments.forEach { segment ->
                append('/')
                append(segment)
            }
        }
    }

    private fun pathSegment(value: String): String {
        require(value.isNotBlank()) { "ID must not be blank" }
        return value.toByteArray(Charsets.UTF_8).joinToString(separator = "") { byte ->
            val unsigned = byte.toInt() and 0xff
            if (unsigned in 0x30..0x39 || unsigned in 0x41..0x5a ||
                unsigned in 0x61..0x7a || unsigned == 0x2d || unsigned == 0x2e ||
                unsigned == 0x5f || unsigned == 0x7e
            ) {
                unsigned.toChar().toString()
            } else {
                "%${unsigned.toString(16).uppercase().padStart(2, '0')}"
            }
        }
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Base URL must not be blank" }

        val uri = runCatching { URI(trimmed) }
            .getOrElse { throw IllegalArgumentException("Base URL must be a valid HTTP or HTTPS URL", it) }
        require(!uri.host.isNullOrBlank()) { "Base URL must include a host" }
        val isHttps = uri.scheme.equals("https", ignoreCase = true)
        val isAllowedHttp = uri.scheme.equals("http", ignoreCase = true) && isPrivateHttpHost(uri.host)
        require(isHttps || isAllowedHttp) {
            "Base URL must use HTTPS, or HTTP with a private network address"
        }
        require(uri.userInfo == null && uri.fragment == null && uri.query == null) {
            "Base URL must not include credentials, query, or fragment"
        }
        return trimmed
    }

    private fun isPrivateHttpHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        if (host.contains(':')) return isPrivateIpv6(host)

        val octets = host.split('.')
        if (octets.size != 4 || octets.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) }) {
            return false
        }
        val values = octets.map { it.toIntOrNull() ?: return false }
        if (values.any { it !in 0..255 }) return false

        return values[0] == 10 ||
            values[0] == 127 ||
            (values[0] == 172 && values[1] in 16..31) ||
            (values[0] == 192 && values[1] == 168)
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val literal = host.removePrefix("[").removeSuffix("]").substringBefore('%')
        val address = runCatching { InetAddress.getByName(literal) }.getOrNull()
        if (address !is Inet6Address) return false
        val first = address.address[0].toInt() and 0xff
        val second = address.address[1].toInt() and 0xff
        return address.isLoopbackAddress ||
            first in 0xfc..0xfd ||
            (first == 0xfe && second in 0x80..0xbf)
    }
}

data class JellyfinRequest(
    val url: String,
    val headers: Map<String, String>
)
