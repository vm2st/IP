package com.vm2st.ip

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No logging, persistent cache or cookies. TLS/hostname validation uses the platform defaults. */
class SafeHttpTransport(private val calls: Call.Factory = newClient()) : HttpFetcher {
    override suspend fun get(url: String, maxBytes: Int): String {
        require(maxBytes in 1..64 * 1024)
        try {
            return withTimeout(12_000) {
                var current = UrlPolicy.validate(url)
                var redirects = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val reply = request(current, maxBytes)
                    if (reply.status in REDIRECTS) {
                        if (redirects++ >= 3) throw LookupFailure(LookupError.REDIRECT)
                        current = redirect(current, reply.location)
                    } else {
                        if (reply.status !in 200..299) throw LookupFailure(LookupError.HTTP, reply.status)
                        return@withTimeout reply.body ?: throw LookupFailure(LookupError.INVALID_RESPONSE)
                    }
                }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive() // Keep parent/lifecycle cancellation as cancellation.
            throw LookupFailure(LookupError.TIMEOUT)
        } catch (e: IOException) {
            throw e.safeLookupError()
        }
    }

    private suspend fun request(url: HttpUrl, maxBytes: Int): TextReply = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url)
            .header("Accept", "text/plain, application/json;q=0.9")
            .header("Cache-Control", "no-cache, no-store")
            .header("User-Agent", "MyIP/${BuildConfig.VERSION_NAME}").get().build()
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val body = if (it.code in 200..299) {
                            val value = it.body ?: throw LookupFailure(LookupError.INVALID_RESPONSE)
                            readUtf8(value, maxBytes) { !continuation.isActive }
                        } else null
                        TextReply(it.code, it.header("Location"), body)
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private data class TextReply(val status: Int, val location: String?, val body: String?)

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private fun newClient(): OkHttpClient {
            val dns = PublicOnlyDns()
            return OkHttpClient.Builder().dns(dns)
                // Validate the destination even when a configured HTTP proxy does its own DNS.
                .addInterceptor { chain ->
                    val url = UrlPolicy.validate(chain.request().url.toString())
                    dns.lookup(url.host)
                    chain.proceed(chain.request())
                }
                .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 2 })
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS).build()
        }

        internal fun redirect(from: HttpUrl, location: String?): HttpUrl {
            if (location.isNullOrBlank() || location.length > UrlPolicy.MAX_URL_LENGTH ||
                location.any { it.code < 33 || it.code == 127 || it == '\\' }) throw LookupFailure(LookupError.REDIRECT)
            val next = from.resolve(location) ?: throw LookupFailure(LookupError.REDIRECT)
            return UrlPolicy.validate(next.toString())
        }

        internal fun readUtf8(body: ResponseBody, maxBytes: Int, canceled: () -> Boolean = { false }): String {
            if (body.contentLength() > maxBytes) throw LookupFailure(LookupError.RESPONSE_TOO_LARGE)
            val output = ByteArrayOutputStream(minOf(maxBytes, 4096))
            body.byteStream().use { stream ->
                val buffer = ByteArray(1024)
                while (true) {
                    if (canceled()) throw IOException("Canceled")
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) throw LookupFailure(LookupError.RESPONSE_TOO_LARGE)
                    output.write(buffer, 0, count)
                }
            }
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                throw LookupFailure(LookupError.INVALID_RESPONSE)
            }
        }
    }
}
