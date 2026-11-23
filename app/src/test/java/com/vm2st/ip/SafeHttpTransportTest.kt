@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vm2st.ip

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SafeHttpTransportTest {
    private class Body(bytes: ByteArray, private val declaredLength: Long = bytes.size.toLong()) : ResponseBody() {
        var closed = false
        private val content = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = declaredLength
        override fun source(): BufferedSource = content
    }
    private class FakeCall(private val req: Request, private val respond: (FakeCall, Callback) -> Unit) : Call {
        var canceled = false
        var callback: Callback? = null
        override fun request() = req
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) { callback = responseCallback; respond(this, responseCallback) }
        override fun cancel() { canceled = true }
        override fun isExecuted() = callback != null
        override fun isCanceled() = canceled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall(req, respond)
    }
    private fun response(call: Call, body: ResponseBody, code: Int = 200, location: String? = null): Response =
        Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body)
            .apply { if (location != null) header("Location", location) }.build()

    @Test fun bodyLimitChecksDeclaredAndActualSizeAndClosesStreams() = runTest {
        for ((declared, content) in listOf(300L to "x", -1L to "x".repeat(257), 0L to "x".repeat(257))) {
            val body = Body(content.toByteArray(), declared)
            val http = SafeHttpTransport(Call.Factory { req -> FakeCall(req) { call, cb -> cb.onResponse(call, response(call, body)) } })
            val failure = runCatching { http.get("https://example.com", 256) }.exceptionOrNull()
            assertEquals(LookupError.RESPONSE_TOO_LARGE, (failure as LookupFailure).error)
            assertTrue(body.closed)
        }
    }
    @Test fun invalidUtf8AndCancellationCloseTheBody() {
        val invalid = Body(byteArrayOf(0xc3.toByte(), 0x28))
        invalid.use { assertEquals(LookupError.INVALID_RESPONSE, assertThrows(LookupFailure::class.java) { SafeHttpTransport.readUtf8(it, 32) }.error) }
        assertTrue(invalid.closed)
        val body = Body("8.8.8.8".toByteArray())
        body.use { assertThrows(IOException::class.java) { SafeHttpTransport.readUtf8(it, 32) { true } } }
        assertTrue(body.closed)
    }
    @Test fun successfulReplyHasNoCookiesOrStoredCacheAndIsClosed() = runTest {
        val body = Body("8.8.8.8".toByteArray())
        val http = SafeHttpTransport(Call.Factory { req ->
            assertNull(req.header("Cookie"))
            assertEquals("no-cache, no-store", req.header("Cache-Control"))
            assertEquals("GET", req.method)
            FakeCall(req) { call, cb -> cb.onResponse(call, response(call, body)) }
        })
        assertEquals("8.8.8.8", http.get("https://example.com", 256))
        assertTrue(body.closed)
    }
    @Test fun redirectsAreBoundedAndAllResponsesClosed() = runTest {
        val bodies = mutableListOf<Body>()
        var count = 0
        val http = SafeHttpTransport(Call.Factory { req -> FakeCall(req) { call, cb ->
            count++
            val body = Body("x".repeat(5000).toByteArray()).also(bodies::add)
            cb.onResponse(call, response(call, body, 302, "/loop"))
        } })
        val failure = runCatching { http.get("https://example.com", 256) }.exceptionOrNull() as LookupFailure
        assertEquals(LookupError.REDIRECT, failure.error)
        assertEquals(4, count)
        assertTrue(bodies.all { it.closed })
    }
    @Test fun redirectNeverFollowsHttpOrLocalDestination() = runTest {
        for (target in listOf("http://example.com/ip", "https://169.254.169.254/latest/meta-data", "https://u:p@example.com")) {
            var calls = 0
            val http = SafeHttpTransport(Call.Factory { req -> FakeCall(req) { call, cb ->
                calls++; cb.onResponse(call, response(call, Body(byteArrayOf()), 302, target))
            } })
            assertTrue(runCatching { http.get("https://example.com", 256) }.isFailure)
            assertEquals(1, calls)
        }
    }
    @Test fun httpErrorsDoNotExposeServerText() = runTest {
        val body = Body("secret server diagnostic".toByteArray())
        val http = SafeHttpTransport(Call.Factory { req -> FakeCall(req) { call, cb -> cb.onResponse(call, response(call, body, 403)) } })
        val failure = runCatching { http.get("https://example.com", 256) }.exceptionOrNull() as LookupFailure
        assertEquals(LookupError.HTTP, failure.error)
        assertEquals(403, failure.status)
        assertFalse(failure.message!!.contains("secret"))
        assertTrue(body.closed)
    }
    @Test fun cancellationCancelsCallAndLateResponseIsClosed() = runTest {
        lateinit var call: FakeCall
        val http = SafeHttpTransport(Call.Factory { req -> FakeCall(req) { _, _ -> }.also { call = it } })
        val result = async { http.get("https://example.com", 256) }
        runCurrent()
        result.cancel()
        runCurrent()
        assertTrue(call.canceled)
        val body = Body("8.8.8.8".toByteArray())
        call.callback!!.onResponse(call, response(call, body))
        assertTrue(body.closed)
        assertTrue(result.isCancelled)
    }
    @Test fun totalDeadlineEndsEvenIfNoHeadersArrive() = runTest {
        lateinit var call: FakeCall
        val http = SafeHttpTransport(Call.Factory { req -> FakeCall(req) { _, _ -> }.also { call = it } })
        val failure = runCatching { http.get("https://example.com", 256) }.exceptionOrNull() as LookupFailure
        assertEquals(LookupError.TIMEOUT, failure.error)
        assertTrue(call.canceled)
    }
}
