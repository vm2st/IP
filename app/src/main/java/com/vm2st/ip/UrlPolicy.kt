package com.vm2st.ip

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

object UrlPolicy {
    const val MAX_URL_LENGTH = 2048
    fun validate(input: String): HttpUrl {
        val text = input.trim()
        if (text.isEmpty() || text.length > MAX_URL_LENGTH ||
            text.any { it.code < 33 || it.code == 127 || it == '\\' }) invalid()
        val url = text.toHttpUrlOrNull() ?: invalid()
        if (url.toString().length > MAX_URL_LENGTH || !url.isHttps || url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty() ||
            url.fragment != null || '@' in text.substringAfter("://").substringBefore('/').substringBefore('?')) invalid()
        // Do not accept OkHttp's lenient conversion of https:host to an absolute URL.
        if (!text.startsWith("https://", ignoreCase = true)) invalid()
        val host = url.host.lowercase(Locale.ROOT).trimEnd('.')
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") || host == "home.arpa" ||
            host.endsWith(".home.arpa") || !isSafeHost(host)) invalid()
        return url
    }

    private fun isSafeHost(host: String): Boolean {
        val ip = IpAddress.parse(host)
        if (ip != null) return ip.isPublic
        if (':' in host || host.all { it in '0'..'9' || it == '.' }) return false
        // Legacy decimal/octal/hex IPv4 representations must not be interpreted by a resolver.
        if (host.split('.').all { it.toLongOrNull() != null || it.startsWith("0x", true) }) return false
        return host.contains('.') && host.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 && label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }
    }

    private fun invalid(): Nothing = throw LookupFailure(LookupError.INVALID_URL)
}

class PublicOnlyDns(private val upstream: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = upstream.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !IpAddress.isPublicBytes(it.address, allowNat64 = true) }) {
            throw UnknownHostException("Non-public destination")
        }
        return addresses
    }
}
