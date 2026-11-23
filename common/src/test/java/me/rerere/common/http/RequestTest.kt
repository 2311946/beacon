package me.rerere.common.http

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okio.Timeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTest {
    private class FakeCall : Call {
        lateinit var callback: Callback
        val cancelled = AtomicBoolean(false)
        private var executed = false
        override fun request(): Request = Request.Builder().url("https://example.invalid/").build()
        override fun execute(): Response = error("Not used")
        override fun enqueue(responseCallback: Callback) {
            executed = true
            callback = responseCallback
        }
        override fun cancel() { cancelled.set(true) }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled.get()
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall()
    }

    private class TrackingBody : ResponseBody() {
        var closed = false
        private val content = object : ForwardingSource(Buffer().writeUtf8("ok")) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = 2L
        override fun source(): BufferedSource = content
    }

    private fun response(call: Call, body: ResponseBody): Response = Response.Builder()
        .request(call.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()

    @Test
    fun `cancelling wait cancels underlying call`() = runBlocking {
        val call = FakeCall()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.await() }
        pending.cancelAndJoin()
        assertTrue(call.isCanceled())
    }

    @Test
    fun `late response after cancellation is closed`() = runBlocking {
        val call = FakeCall()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.await() }
        pending.cancelAndJoin()
        val body = TrackingBody()
        call.callback.onResponse(call, response(call, body))
        assertTrue(body.closed)
    }

    @Test
    fun `response cancelled before dispatch is closed`() = runBlocking {
        val call = FakeCall()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.await() }
        val body = TrackingBody()
        call.callback.onResponse(call, response(call, body))
        pending.cancelAndJoin()
        assertTrue(body.closed)
    }

    @Test
    fun `successful response remains owned by caller`() = runBlocking {
        val call = FakeCall()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.await() }
        val body = TrackingBody()
        val expected = response(call, body)
        call.callback.onResponse(call, expected)
        val actual = pending.await()
        assertSame(expected, actual)
        assertFalse(call.isCanceled())
        assertFalse(body.closed)
        actual.close()
        assertTrue(body.closed)
    }
}
