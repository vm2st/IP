package com.vm2st.ip

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

enum class LookupError { INVALID_URL, NETWORK, TIMEOUT, HTTP, INVALID_IP, INVALID_RESPONSE, RESPONSE_TOO_LARGE, REDIRECT }
class LookupFailure(val error: LookupError, val status: Int? = null) : IOException(error.name)

data class GeoInfo(val country: String, val region: String, val countryCode: String) {
    val label: String get() {
        val text = listOf(country, region).filter { it.isNotEmpty() }.distinct().joinToString(", ")
        val flag = if (countryCode.length == 2 && countryCode.all { it in 'A'..'Z' }) {
            countryCode.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
        } else ""
        return listOf(flag, text).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

interface IpDataSource {
    suspend fun lookupIp(url: String): IpAddress
    suspend fun lookupLocation(ip: IpAddress): GeoInfo
}
fun interface HttpFetcher { suspend fun get(url: String, maxBytes: Int): String }

class IpRepository(private val http: HttpFetcher) : IpDataSource {
    override suspend fun lookupIp(url: String): IpAddress =
        IpAddress.parsePublic(http.get(UrlPolicy.validate(url).toString(), 256)) ?: throw LookupFailure(LookupError.INVALID_IP)

    override suspend fun lookupLocation(ip: IpAddress): GeoInfo {
        if (!ip.isPublic) throw LookupFailure(LookupError.INVALID_IP)
        val endpoint = "https://get.geojs.io/v1/ip/geo/".toHttpUrl().newBuilder()
            .addPathSegment("${ip.value}.json").build()
        return parseLocation(http.get(endpoint.toString(), 16 * 1024), ip)
    }

    companion object {
        fun parseLocation(text: String, expected: IpAddress): GeoInfo {
            try {
                val json = JSONObject(text)
                val actual = (json.opt("ip") as? String)?.let(IpAddress::parse)
                if (actual != expected) throw LookupFailure(LookupError.INVALID_RESPONSE)
                fun field(key: String, max: Int): String {
                    val raw = json.opt(key) as? String ?: return ""
                    // Network text is plain text; strip control, bidi and zero-width formatting characters.
                    return raw.take(1024).filter { ch ->
                        !Character.isISOControl(ch) && Character.getType(ch) != Character.FORMAT.toInt() &&
                            !Character.isSurrogate(ch)
                    }.trim().replace(Regex("\\s+"), " ").take(max)
                }
                val code = (json.opt("country_code") as? String).orEmpty().takeIf { v ->
                    v.length == 2 && v.all { it in 'a'..'z' || it in 'A'..'Z' }
                }?.uppercase(Locale.ROOT).orEmpty()
                return GeoInfo(field("country", 80), field("region", 128), code)
            } catch (e: LookupFailure) { throw e }
            catch (e: Exception) { throw LookupFailure(LookupError.INVALID_RESPONSE) }
        }
    }
}

fun Throwable.safeLookupError(): LookupFailure = when (this) {
    is LookupFailure -> this
    is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> LookupFailure(LookupError.TIMEOUT)
    else -> LookupFailure(LookupError.NETWORK)
}
