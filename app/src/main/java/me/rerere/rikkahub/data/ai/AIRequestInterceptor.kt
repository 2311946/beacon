package me.rerere.rikkahub.data.ai

import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.Locale

class AIRequestInterceptor : Interceptor {
    private val diagnostics by lazy { RequestPrefixDiagnostics() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        try {
            diagnose(request)
        } catch (_: Exception) {
            // 诊断失败不影响请求，也不记录可能携带秘密的异常消息。
        }
        return chain.proceed(request)
    }

    private fun diagnose(request: Request) {
        val sessions = request.headers.values("X-Session-ID")
        val session = sessions.singleOrNull()?.takeIf { it.isNotBlank() } ?: return
        val body = request.body ?: return
        if (body.isOneShot() || body.isDuplex()) return
        if (request.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) return
        val type = request.header("Content-Type")?.toMediaTypeOrNull() ?: body.contentType() ?: return
        if (type.subtype != "json" && !type.subtype.endsWith("+json")) return
        if (type.charset(Charsets.UTF_8) != Charsets.UTF_8) return
        if (body.contentLength() > RequestPrefixDiagnostics.MAX_BODY_BYTES) return

        val bytes = Buffer()
        // 即使长度未知或声明不准确，也不会为诊断缓存超过 2 MiB 的正文。
        val boundedSink = object : Sink {
            override fun write(source: Buffer, byteCount: Long) {
                if (byteCount > RequestPrefixDiagnostics.MAX_BODY_BYTES - bytes.size) {
                    throw IOException("Diagnostic body limit")
                }
                bytes.write(source, byteCount)
            }

            override fun flush() = Unit
            override fun close() = Unit
            override fun timeout(): Timeout = Timeout.NONE
        }.buffer()
        try {
            // Provider 已合并 custom body 并生成可重复的 UTF-8 RequestBody；不重建 JSON 或请求。
            body.writeTo(boundedSink)
            boundedSink.flush()
            // 包含自定义认证 header，避免遗漏未知名称的凭据。变化的非认证 header 会保守地分区。
            val credentials = request.headers.names()
                .filterNot { it.equals("Content-Length", true) || it.equals("Content-Type", true) || it.equals("X-Session-ID", true) }
                .sortedBy { it.lowercase(Locale.ROOT) }
                .flatMap { name ->
                    val values = request.headers.values(name)
                    listOf(name.lowercase(Locale.ROOT), values.size.toString()) + values
                }
            diagnostics.observe(
                endpoint = "${request.method} ${request.url}",
                sessionId = session,
                credentials = credentials,
                body = bytes.readByteArray(),
            )?.let { Log.d("AIRequestPrefix", it.safeSummary()) }
        } finally {
            bytes.clear()
            boundedSink.buffer.clear()
        }
    }
}
