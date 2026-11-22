package me.rerere.rikkahub.data.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.model.MemoryEmbeddingSettings
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MemoryEmbeddingClientTest {
    @Test
    fun `端点允许完整HTTPS地址和显式本地HTTP地址`() {
        listOf(
            "https://api.openai.com/v1/embeddings",
            "https://example.com/embeddings",
            " https://example.com/custom/v1/embeddings ",
            "http://localhost:8080/v1/embeddings",
            "http://127.0.0.1:8080/embeddings",
            "http://[::1]:8080/v1/embeddings",
        ).forEach { assertTrue(it, validEmbeddingEndpoint(it)) }
    }

    @Test
    fun `端点拒绝远程明文认证信息查询片段和不完整路径`() {
        listOf(
            "http://example.com/v1/embeddings",
            "http://192.168.1.2/embeddings",
            "http://localhost.example.com/embeddings",
            "https://user@example.com/embeddings",
            "https://:secret@example.com/embeddings",
            "https://@example.com/embeddings",
            "https://example.com/embeddings?key=secret",
            "https://example.com/embeddings?",
            "https://example.com/embeddings#part",
            "https://example.com/embeddings#",
            "https://example.com/v1",
            "https://example.com/v1/embeddings/",
            "https://example.com/not-embeddings",
            "https://example.com/%65mbeddings",
            "file:///embeddings",
            "//example.com/embeddings",
            "not a URL",
            "",
        ).forEach { assertFalse(it, validEmbeddingEndpoint(it)) }
    }

    @Test
    fun `profile规范trim且不依赖key开关或检索权重`() {
        val config = MemoryEmbeddingSettings(
            endpoint = "https://example.com/v1/embeddings",
            apiKey = "不可出现在标识中的测试密钥",
            model = "test-embedding-model",
        )
        val profile = embeddingProfile(config)
        assertEquals("sha256:".length + 64, profile.length)
        assertTrue(profile.matches(Regex("sha256:[0-9a-f]{64}")))
        assertFalse(profile.contains(config.apiKey))
        assertFalse(profile.contains(config.endpoint))
        assertFalse(profile.contains(config.model))
        assertEquals(profile, embeddingProfile(config.copy(apiKey = "另一个密钥", enabled = true, semanticWeight = 0.2)))
        assertEquals(profile, embeddingProfile(config.copy(endpoint = " ${config.endpoint} ", model = " ${config.model} ")))
        assertNotEquals(profile, embeddingProfile(config.copy(endpoint = "https://example.com/v2/embeddings")))
        assertNotEquals(profile, embeddingProfile(config.copy(model = "another-model")))
    }

    @Test
    fun `向量编码使用小端float32且可无损往返`() {
        val vector = floatArrayOf(1f, -2.5f, Float.MIN_VALUE, Float.MAX_VALUE)
        val bytes = MemoryVectorCodec.encode(vector)
        assertArrayEquals(byteArrayOf(0, 0, -128, 63, 0, 0, 32, -64), bytes.copyOfRange(0, 8))
        assertArrayEquals(vector, MemoryVectorCodec.decode(bytes, vector.size), 0f)
    }

    @Test
    fun `向量编解码允许边界维度和极小非零范数`() {
        val maximum = FloatArray(8192) { 1f }
        assertArrayEquals(maximum, MemoryVectorCodec.decode(MemoryVectorCodec.encode(maximum), 8192), 0f)
        val minimum = floatArrayOf(Float.MIN_VALUE)
        assertArrayEquals(minimum, MemoryVectorCodec.decode(MemoryVectorCodec.encode(minimum), 1), 0f)
    }

    @Test
    fun `编码拒绝空超维零范数和非有限向量`() {
        listOf(
            floatArrayOf(),
            FloatArray(8193) { 1f },
            floatArrayOf(0f, -0f),
            floatArrayOf(Float.NaN),
            floatArrayOf(Float.POSITIVE_INFINITY),
            floatArrayOf(Float.NEGATIVE_INFINITY),
        ).forEach { vector ->
            assertTrue(runCatching { MemoryVectorCodec.encode(vector) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `解码拒绝错误字节数维度非有限及零范数`() {
        val valid = MemoryVectorCodec.encode(floatArrayOf(1f))
        listOf(-1, 0, 2, 8193, Int.MAX_VALUE).forEach { assertNull(MemoryVectorCodec.decode(valid, it)) }
        assertNull(MemoryVectorCodec.decode(valid + byteArrayOf(0), 1))
        assertNull(MemoryVectorCodec.decode(ByteArray(4), 1))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
            assertNull(MemoryVectorCodec.decode(bytes, 1))
        }
    }

    @Test
    fun `余弦支持平行正交反向和极端有限数值`() {
        assertEquals(1.0, MemoryVectorCodec.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f))!!, 1e-12)
        assertEquals(0.0, MemoryVectorCodec.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))!!, 0.0)
        assertEquals(-1.0, MemoryVectorCodec.cosine(floatArrayOf(1f), floatArrayOf(-1f))!!, 0.0)
        assertEquals(1.0, MemoryVectorCodec.cosine(floatArrayOf(Float.MAX_VALUE), floatArrayOf(Float.MAX_VALUE))!!, 1e-12)
        assertEquals(1.0, MemoryVectorCodec.cosine(floatArrayOf(Float.MIN_VALUE), floatArrayOf(Float.MIN_VALUE))!!, 1e-12)
    }

    @Test
    fun `余弦遇到无效向量或维度不一致返回null`() {
        assertNull(MemoryVectorCodec.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)))
        assertNull(MemoryVectorCodec.cosine(floatArrayOf(), floatArrayOf()))
        assertNull(MemoryVectorCodec.cosine(floatArrayOf(0f), floatArrayOf(1f)))
        assertNull(MemoryVectorCodec.cosine(floatArrayOf(1f), floatArrayOf(Float.NaN)))
        assertNull(MemoryVectorCodec.cosine(FloatArray(8193) { 1f }, FloatArray(8193) { 1f }))
    }

    @Test
    fun `响应按index而非返回顺序重排`() {
        val result = parse(
            """{"data":[{"index":1,"embedding":[3,4]},{"index":0,"embedding":[1,2]}]}""",
            2,
        )
        assertArrayEquals(floatArrayOf(1f, 2f), result[0], 0f)
        assertArrayEquals(floatArrayOf(3f, 4f), result[1], 0f)
    }

    @Test
    fun `响应拒绝重复缺失越界非整数和字符串index`() {
        listOf(
            """{"data":[{"index":0,"embedding":[1]},{"index":0,"embedding":[2]}]}""" to 2,
            """{"data":[{"index":0,"embedding":[1]}]}""" to 2,
            """{"data":[{"index":1,"embedding":[1]}]}""" to 1,
            """{"data":[{"index":-1,"embedding":[1]}]}""" to 1,
            """{"data":[{"embedding":[1]}]}""" to 1,
            """{"data":[{"index":0.5,"embedding":[1]}]}""" to 1,
            """{"data":[{"index":"0","embedding":[1]}]}""" to 1,
        ).forEach { (body, count) -> assertInvalidResponse(body, count) }
    }

    @Test
    fun `响应拒绝非数值非有限零范数和不一致维度`() {
        listOf("null", "true", "\"1\"", "{}", "[]", "1e999", "1e39", "NaN", "Infinity").forEach { value ->
            assertInvalidResponse("""{"data":[{"index":0,"embedding":[$value]}]}""")
        }
        assertInvalidResponse("""{"data":[{"index":0,"embedding":[0,-0]}]}""")
        assertInvalidResponse("""{"data":[{"index":0,"embedding":[]}]}""")
        assertInvalidResponse("""{"data":[{"index":0,"embedding":[1]},{"index":1,"embedding":[1,2]}]}""", 2)
        val values = List(8193) { "1" }.joinToString(",")
        assertInvalidResponse("""{"data":[{"index":0,"embedding":[$values]}]}""")
    }

    @Test
    fun `响应拒绝结构错误且错误不携带响应或异常链`() {
        listOf("{}", "[]", "{", "null", "{\"data\":null}", "{\"data\":{}}", "{\"data\":[null]}").forEach {
            assertInvalidResponse(it)
        }
        val secret = "测试响应中的私密正文"
        val error = runCatching { parse(secret) }.exceptionOrNull()!!
        assertFalse(error.toString().contains(secret))
        assertNull(error.cause)
        assertTrue(runCatching { parseEmbeddingResponse(ByteArray(4 * 1024 * 1024 + 1), 1) }.isFailure)
        assertTrue(runCatching { parseEmbeddingResponse(byteArrayOf(-1), 1) }.isFailure)
    }

    @Test
    fun `禁用配置和空输入不会产生网络请求`() = runBlocking {
        val base = OkHttpClient.Builder().addInterceptor { error("不应调用基础拦截器") }.build()
        val embedder = MemoryEmbeddingClient(base)
        assertTrue(embedder.embed(MemoryEmbeddingSettings(enabled = false, endpoint = "无效"), listOf("text")).isEmpty())
        assertTrue(embedder.embed(MemoryEmbeddingSettings(enabled = true, endpoint = "无效"), emptyList()).isEmpty())
    }

    @Test
    fun `本地请求发送JSON格式并隔离基础客户端拦截器`() = runBlocking {
        val requestBody = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val method = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/embeddings") { exchange ->
            exchange.use {
                method.set(exchange.requestMethod)
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                requestBody.set(exchange.requestBody.readBytes().decodeToString())
                val response = """{"data":[{"index":1,"embedding":[3,4]},{"index":0,"embedding":[1,2]}]}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.write(response)
            }
        }
        server.start()
        try {
            val base = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .addInterceptor { error("不应调用基础应用拦截器") }
                .addNetworkInterceptor { error("不应调用基础网络拦截器") }
                .build()
            val config = MemoryEmbeddingSettings(
                enabled = true,
                endpoint = "http://127.0.0.1:${server.address.port}/v1/embeddings",
                apiKey = "test-local-key",
                model = " local-model ",
            )
            val client = MemoryEmbeddingClient(base)
            val result = client.embed(config, listOf("第一条文本", "第二条文本"))
            assertEquals("POST", method.get())
            assertEquals("Bearer test-local-key", authorization.get())
            val payload = Json.parseToJsonElement(requireNotNull(requestBody.get())).jsonObject
            assertEquals("local-model", payload["model"]?.jsonPrimitive?.content)
            assertEquals("float", payload["encoding_format"]?.jsonPrimitive?.content)
            assertEquals(listOf("第一条文本", "第二条文本"), payload["input"]?.jsonArray?.map { it.jsonPrimitive.content })
            assertArrayEquals(floatArrayOf(1f, 2f), result[0], 0f)
            assertArrayEquals(floatArrayOf(3f, 4f), result[1], 0f)
            client.embed(config.copy(apiKey = ""), listOf("第一条文本", "第二条文本"))
            assertNull(authorization.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `本地响应重定向不跟随且失败不包含密钥或响应正文`() = runBlocking {
        val followed = AtomicInteger()
        val privateBody = "服务端私密错误正文"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/embeddings") { exchange ->
            exchange.use {
                exchange.requestBody.readBytes()
                exchange.responseHeaders.add("Location", "/other/embeddings")
                val bytes = privateBody.toByteArray()
                exchange.sendResponseHeaders(307, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.createContext("/other/embeddings") { exchange ->
            followed.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = MemoryEmbeddingClient(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
            val config = MemoryEmbeddingSettings(
                enabled = true,
                endpoint = "http://127.0.0.1:${server.address.port}/v1/embeddings",
                apiKey = "private-test-key",
            )
            val error = runCatching { client.embed(config, listOf("本地测试")) }.exceptionOrNull()!!
            assertEquals(0, followed.get())
            assertFalse(error.toString().contains(privateBody))
            assertFalse(error.toString().contains(config.apiKey))
            // 协程堆栈恢复可复制同类型异常；整条链只允许脱敏信息。
            generateSequence(error) { it.cause }.take(8).forEach {
                assertFalse(it.toString().contains(privateBody))
                assertFalse(it.toString().contains(config.apiKey))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `协程取消会取消底层Call`() = runBlocking {
        val releaseResponse = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/embeddings") { exchange ->
            releaseResponse.await()
            runCatching { exchange.sendResponseHeaders(500, -1) }
            exchange.close()
        }
        server.start()
        val dispatcher = okhttp3.Dispatcher()
        val base = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dispatcher(dispatcher).build()
        try {
            val config = MemoryEmbeddingSettings(
                enabled = true,
                endpoint = "http://127.0.0.1:${server.address.port}/embeddings",
            )
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                MemoryEmbeddingClient(base).embed(config, listOf("取消测试"))
            }
            val activeCall = (dispatcher.runningCalls() + dispatcher.queuedCalls()).single()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue(activeCall.isCanceled())
        } finally {
            releaseResponse.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `未知长度本地响应超过四MiB会被拒绝`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/embeddings") { exchange ->
            exchange.use {
                exchange.requestBody.readBytes()
                exchange.sendResponseHeaders(200, 0)
                runCatching { exchange.responseBody.write(ByteArray(4 * 1024 * 1024 + 1) { 32 }) }
            }
        }
        server.start()
        try {
            val client = MemoryEmbeddingClient(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
            val config = MemoryEmbeddingSettings(
                enabled = true,
                endpoint = "http://127.0.0.1:${server.address.port}/embeddings",
            )
            val error = runCatching { client.embed(config, listOf("本地测试")) }.exceptionOrNull()
            assertTrue(error is java.io.IOException)
            // 协程堆栈恢复可能增加一个同类型的脱敏 cause。
            generateSequence(error) { it.cause }.take(8).forEach {
                assertFalse(it.toString().contains(config.endpoint))
                assertFalse(it.toString().contains("本地测试"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `连接测试使用禁用草稿的当前地址密钥模型并仅发送固定文本`() = runBlocking {
        val requestBody = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/custom/v1/embeddings") { exchange ->
            exchange.use {
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                requestBody.set(exchange.requestBody.readBytes().decodeToString())
                val bytes = """{"data":[{"index":0,"embedding":[1,2,3]}]}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            val draft = MemoryEmbeddingSettings(
                enabled = false,
                endpoint = "http://127.0.0.1:${server.address.port}/custom/v1/embeddings",
                apiKey = "unsaved-draft-key",
                model = " unsaved-model ",
                semanticWeight = Double.NaN,
            )
            val client = MemoryEmbeddingClient(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
            assertEquals(MemoryEmbeddingConnectionResult.Success(3), testMemoryEmbeddingConnection(client, draft))
            assertFalse(draft.enabled)
            assertEquals("Bearer unsaved-draft-key", authorization.get())
            val payload = Json.parseToJsonElement(requestBody.get()).jsonObject
            assertEquals("unsaved-model", payload["model"]?.jsonPrimitive?.content)
            assertEquals(listOf("测试连接"), payload["input"]?.jsonArray?.map { it.jsonPrimitive.content })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `连接测试配置无效时不外发且错误不包含草稿内容`() = runBlocking {
        val embedder = object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> =
                error("不应产生请求")
        }
        val invalid = testMemoryEmbeddingConnection(
            embedder,
            MemoryEmbeddingSettings(endpoint = "https://secret.invalid/embeddings?key=private"),
        ) as MemoryEmbeddingConnectionResult.Failure
        assertTrue(invalid.message.contains("Endpoint 无效"))
        assertFalse(invalid.message.contains("private"))
        val emptyModel = testMemoryEmbeddingConnection(embedder, MemoryEmbeddingSettings(model = " "))
        assertEquals(MemoryEmbeddingConnectionResult.Failure("模型不能为空"), emptyModel)
    }

    @Test
    fun `HTTP诊断区分认证模型地址限流和服务错误且不显示服务端正文`() = runBlocking {
        val status = AtomicInteger(401)
        val body = AtomicReference("""{"error":{"code":"model_not_found","message":"private-key-and-input"}}""")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/embeddings") { exchange ->
            exchange.use {
                exchange.requestBody.readBytes()
                val bytes = body.get().toByteArray()
                exchange.sendResponseHeaders(status.get(), bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            val client = MemoryEmbeddingClient(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
            val draft = MemoryEmbeddingSettings(endpoint = "http://127.0.0.1:${server.address.port}/embeddings")
            listOf(
                401 to "API Key 错误",
                403 to "访问被拒绝",
                400 to "模型不存在",
                404 to "模型不存在",
                429 to "额度不足",
                504 to "超时",
                500 to "暂时不可用",
                307 to "未跟随跳转",
            ).forEach { (code, expected) ->
                status.set(code)
                val result = testMemoryEmbeddingConnection(client, draft) as MemoryEmbeddingConnectionResult.Failure
                assertTrue(result.message, result.message.contains(expected))
                assertTrue(result.message.contains("HTTP $code"))
                assertFalse(result.message.contains("private-key-and-input"))
                assertFalse(result.message.contains(draft.endpoint))
            }
            status.set(404)
            body.set("""{"error":{"message":"model_not_found private-key-and-input"}}""")
            val unknown = testMemoryEmbeddingConnection(client, draft) as MemoryEmbeddingConnectionResult.Failure
            assertTrue(unknown.message.contains("接口地址或模型不存在"))
            body.set(" ".repeat(16 * 1024 + 1))
            val oversized = testMemoryEmbeddingConnection(client, draft) as MemoryEmbeddingConnectionResult.Failure
            assertTrue(oversized.message.contains("HTTP 404"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `网络诊断按异常类型分类且不携带原异常信息`() {
        listOf(
            java.net.SocketTimeoutException("private") to "网络超时",
            java.io.InterruptedIOException("private") to "网络超时",
            java.net.UnknownHostException("private") to "域名解析失败",
            java.net.ConnectException("private") to "无法连接服务器",
            javax.net.ssl.SSLHandshakeException("private") to "TLS 连接失败",
            java.io.IOException("private") to "网络请求失败",
        ).forEach { (raw, expected) ->
            val safe = embeddingNetworkError(raw)
            assertTrue(safe.message!!, safe.message!!.contains(expected))
            assertFalse(safe.toString().contains("private"))
            assertNull(safe.cause)
        }
    }

    @Test
    fun `连接测试实际读取超时返回明确提示`() = runBlocking {
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/embeddings") { exchange ->
            exchange.requestBody.readBytes()
            release.await()
            exchange.close()
        }
        server.start()
        try {
            val base = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .readTimeout(100, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            val draft = MemoryEmbeddingSettings(endpoint = "http://127.0.0.1:${server.address.port}/embeddings")
            val result = testMemoryEmbeddingConnection(MemoryEmbeddingClient(base), draft)
                as MemoryEmbeddingConnectionResult.Failure
            assertTrue(result.message, result.message.contains("网络超时"))
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `连接测试取消不会转成失败且会取消底层请求`() = runBlocking {
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/embeddings") { exchange ->
            release.await()
            exchange.close()
        }
        server.start()
        try {
            val dispatcher = okhttp3.Dispatcher()
            val client = MemoryEmbeddingClient(OkHttpClient.Builder().dispatcher(dispatcher).proxy(Proxy.NO_PROXY).build())
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                testMemoryEmbeddingConnection(
                    client,
                    MemoryEmbeddingSettings(endpoint = "http://127.0.0.1:${server.address.port}/embeddings"),
                )
            }
            val call = (dispatcher.runningCalls() + dispatcher.queuedCalls()).single()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `连接测试不向UI暴露未知异常或非法向量`() = runBlocking {
        for (vectors in listOf(emptyList(), listOf(floatArrayOf(0f)), listOf(floatArrayOf(Float.NaN)))) {
            val fake = object : MemoryEmbedder {
                override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>) = vectors
            }
            assertTrue(testMemoryEmbeddingConnection(fake, MemoryEmbeddingSettings()) is MemoryEmbeddingConnectionResult.Failure)
        }
        val fake = object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> =
                throw java.io.IOException("private-api-key-and-response")
        }
        val result = testMemoryEmbeddingConnection(fake, MemoryEmbeddingSettings()) as MemoryEmbeddingConnectionResult.Failure
        assertFalse(result.message.contains("private"))
    }

    private fun parse(body: String, count: Int = 1): List<FloatArray> =
        parseEmbeddingResponse(body.toByteArray(Charsets.UTF_8), count)

    private fun assertInvalidResponse(body: String, count: Int = 1) {
        val error = runCatching { parse(body, count) }.exceptionOrNull()
        assertTrue(body, error is IllegalStateException)
        assertNull(error?.cause)
    }
}
