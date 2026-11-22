package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.MemoryEmbeddingSettings
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface MemoryEmbedder {
    suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray>
}

internal class MemoryEmbeddingClient(
    baseClient: OkHttpClient,
) : MemoryEmbedder {
    private val client = baseClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .eventListener(EventListener.NONE)
        .authenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .build()

    override suspend fun embed(
        config: MemoryEmbeddingSettings,
        inputs: List<String>,
    ): List<FloatArray> {
        if (!config.enabled || inputs.isEmpty()) return emptyList()
        val endpoint = config.endpoint.trim()
        require(validEmbeddingEndpoint(endpoint)) { "记忆嵌入端点无效" }
        val model = config.model.trim()
        require(model.isNotEmpty()) { "记忆嵌入模型不能为空" }

        val payload = buildJsonObject {
            put("model", model)
            put("input", buildJsonArray { inputs.forEach { add(it) } })
            put("encoding_format", "float")
        }.toString()
        val request = try {
            Request.Builder()
                .url(endpoint)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    config.apiKey.trim().takeIf { it.isNotEmpty() }?.let { key ->
                        header("Authorization", "Bearer $key")
                    }
                }
                .build()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("记忆嵌入请求配置无效")
        }
        val call = client.newCall(request)
        return call.awaitEmbeddings(inputs.size)
    }

    private suspend fun Call.awaitEmbeddings(expectedCount: Int): List<FloatArray> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(embeddingNetworkError(e))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { checkedResponse ->
                            if (checkedResponse.code != 200) {
                                val errorBody = if (checkedResponse.code == 400 || checkedResponse.code == 404) {
                                    runCatching {
                                        readLimited(checkedResponse.body.byteStream(), 16 * 1024)
                                    }.getOrNull()
                                } else null
                                throw embeddingHttpError(checkedResponse.code, errorBody)
                            }
                            val body = checkedResponse.body
                            require(body.contentLength() <= MAX_EMBEDDING_RESPONSE_BYTES)
                            parseEmbeddingResponse(readLimited(body.byteStream()), expectedCount)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) {
                            val safeError = when (error) {
                                is MemoryEmbeddingException -> error
                                is IOException -> embeddingNetworkError(error)
                                else -> MemoryEmbeddingException("记忆嵌入响应无效：未返回合法向量")
                            }
                            continuation.resumeWithException(safeError)
                        }
                    }
                }
            })
        }

    private fun readLimited(
        input: java.io.InputStream,
        limit: Int = MAX_EMBEDDING_RESPONSE_BYTES,
    ): ByteArray {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buffer, 0, minOf(buffer.size, limit - total + 1))
                if (read < 0) break
                total += read
                if (total > limit) throw IllegalStateException("记忆嵌入响应过大")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

// 仅携带本地生成的诊断，不保留服务端正文、URL、密钥或原始异常链。
internal class MemoryEmbeddingException(message: String) : IOException(message)

internal fun embeddingNetworkError(error: IOException): MemoryEmbeddingException =
    MemoryEmbeddingException(
        when (error) {
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "网络超时，请检查网络或稍后重试"
            is java.net.UnknownHostException -> "域名解析失败，请检查 Endpoint 和网络"
            is java.net.ConnectException -> "无法连接服务器，请检查地址、端口和网络"
            is javax.net.ssl.SSLException -> "TLS 连接失败，请检查服务器证书和 HTTPS 配置"
            else -> "网络请求失败，请检查网络、代理和 Endpoint"
        }
    )

internal fun embeddingHttpError(status: Int, body: ByteArray?): MemoryEmbeddingException {
    // 只识别标准错误代码白名单，不显示 message（可能包含密钥或输入文本）。
    val modelMissing = if (status == 400 || status == 404) {
        runCatching {
            val root = body?.let { Json.parseToJsonElement(it.decodeToString()) } as? JsonObject
            val error = root?.get("error") as? JsonObject
            listOf("code", "type").any { field ->
                val value = error?.get(field) as? JsonPrimitive
                value?.isString == true && value.content in setOf("model_not_found", "invalid_model", "model_not_exist")
            }
        }.getOrDefault(false)
    } else false
    val reason = when {
        modelMissing -> "模型不存在或当前 API Key 无权使用该模型"
        status == 401 -> "API Key 错误或已失效"
        status == 403 -> "访问被拒绝，请检查 API Key 权限或服务商限制"
        status == 404 -> "接口地址或模型不存在，请检查 Endpoint 和模型名称"
        status == 400 || status == 422 -> "请求参数无效，请检查模型名称和 Embedding 接口兼容性"
        status == 408 || status == 504 -> "服务端请求超时，请稍后重试"
        status == 429 -> "请求受限或额度不足，请检查服务商配额"
        status in 300..399 -> "服务器返回重定向；为保护密钥，未跟随跳转，请填写最终接口地址"
        status in 500..599 -> "Embedding 服务暂时不可用，请稍后重试"
        else -> "Embedding 请求失败"
    }
    return MemoryEmbeddingException("$reason（HTTP $status）")
}

internal sealed interface MemoryEmbeddingConnectionResult {
    data class Success(val dimensions: Int) : MemoryEmbeddingConnectionResult
    data class Failure(val message: String) : MemoryEmbeddingConnectionResult
}

internal suspend fun testMemoryEmbeddingConnection(
    embedder: MemoryEmbedder,
    draft: MemoryEmbeddingSettings,
): MemoryEmbeddingConnectionResult {
    if (!validEmbeddingEndpoint(draft.endpoint)) {
        return MemoryEmbeddingConnectionResult.Failure("Endpoint 无效，请填写完整的 HTTPS /embeddings 地址或本地 HTTP 地址")
    }
    if (draft.model.isBlank()) return MemoryEmbeddingConnectionResult.Failure("模型不能为空")
    return try {
        // 只为本次显式测试启用请求，不保存设置、不访问记忆库、不触发后台索引。
        val vector = embedder.embed(draft.copy(enabled = true), listOf("测试连接")).single()
        validateVector(vector)
        MemoryEmbeddingConnectionResult.Success(vector.size)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: MemoryEmbeddingException) {
        MemoryEmbeddingConnectionResult.Failure(error.message ?: "Embedding 连接测试失败")
    } catch (_: IllegalArgumentException) {
        MemoryEmbeddingConnectionResult.Failure("请求配置或响应向量无效，请检查 Endpoint、API Key 和模型")
    } catch (_: Exception) {
        MemoryEmbeddingConnectionResult.Failure("连接测试失败：未收到合法向量，请检查接口兼容性")
    }
}

internal fun parseEmbeddingResponse(bytes: ByteArray, expectedCount: Int): List<FloatArray> {
    try {
        require(expectedCount > 0 && bytes.size <= MAX_EMBEDDING_RESPONSE_BYTES)
        val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
            ?: error("记忆嵌入响应不是对象")
        val data = root["data"] as? JsonArray ?: error("记忆嵌入响应缺少数据")
        require(data.size == expectedCount)
        val vectors = arrayOfNulls<FloatArray>(expectedCount)
        var dimensions = 0
        for (item in data) {
            val entry = item as? JsonObject ?: error("记忆嵌入条目无效")
            val index = entry["index"]?.let(::jsonInt) ?: error("记忆嵌入条目缺少索引")
            require(index in vectors.indices && vectors[index] == null)
            val embedding = entry["embedding"] as? JsonArray ?: error("记忆嵌入条目缺少向量")
            require(embedding.size in 1..MAX_VECTOR_DIMENSIONS)
            if (dimensions == 0) dimensions = embedding.size
            require(embedding.size == dimensions)
            val vector = FloatArray(dimensions) { position ->
                val number = embedding[position] as? JsonPrimitive ?: error("记忆嵌入数值无效")
                require(!number.isString)
                val value = number.content.toDoubleOrNull() ?: error("记忆嵌入数值无效")
                require(value.isFinite())
                value.toFloat().also { require(it.isFinite()) }
            }
            validateVector(vector)
            vectors[index] = vector
        }
        return vectors.map { requireNotNull(it) }
    } catch (_: Exception) {
        // 解析异常可能附带原始响应片段，不向上层保留异常链。
        throw IllegalStateException("记忆嵌入响应无效")
    }
}

internal fun embeddingProfile(config: MemoryEmbeddingSettings): String {
    val canonical = "endpoint=${config.endpoint.trim()}\nmodel=${config.model.trim()}\nchunker=1\nencoding=float32le-v1"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "sha256:$digest"
}

internal fun validEmbeddingEndpoint(endpoint: String): Boolean {
    val trimmed = endpoint.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
    if (uri.rawAuthority == null || uri.rawAuthority.contains('@')) return false
    val url = trimmed.toHttpUrlOrNull() ?: return false
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || !url.encodedPath.endsWith("/embeddings")) return false
    return when (url.scheme) {
        "https" -> true
        "http" -> url.host == "localhost" || url.host == "127.0.0.1" || url.host == "::1"
        else -> false
    }
}

internal object MemoryVectorCodec {
    fun encode(vector: FloatArray): ByteArray {
        validateVector(vector)
        return ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { vector.forEach { putFloat(it) } }
            .array()
    }

    fun decode(bytes: ByteArray, dimensions: Int): FloatArray? {
        if (dimensions !in 1..MAX_VECTOR_DIMENSIONS || bytes.size != dimensions * Float.SIZE_BYTES) return null
        val vector = FloatArray(dimensions)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).let { buffer ->
            repeat(dimensions) { vector[it] = buffer.float }
        }
        return runCatching {
            validateVector(vector)
            vector
        }.getOrNull()
    }

    fun cosine(a: FloatArray, b: FloatArray): Double? {
        if (a.size != b.size || a.size !in 1..MAX_VECTOR_DIMENSIONS) return null
        return runCatching {
            validateVector(a)
            validateVector(b)
            var dot = 0.0
            var aNorm = 0.0
            var bNorm = 0.0
            for (index in a.indices) {
                dot += a[index].toDouble() * b[index].toDouble()
                aNorm += a[index].toDouble() * a[index].toDouble()
                bNorm += b[index].toDouble() * b[index].toDouble()
            }
            val result = dot / (kotlin.math.sqrt(aNorm) * kotlin.math.sqrt(bNorm))
            result.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0)
        }.getOrNull()
    }
}

private const val MAX_EMBEDDING_RESPONSE_BYTES = 4 * 1024 * 1024
private const val MAX_VECTOR_DIMENSIONS = 8192

private fun validateVector(vector: FloatArray) {
    require(vector.size in 1..MAX_VECTOR_DIMENSIONS) { "记忆嵌入维度无效" }
    var norm = 0.0
    vector.forEach { value ->
        require(value.isFinite()) { "记忆嵌入包含非有限数值" }
        norm += value.toDouble() * value.toDouble()
    }
    require(norm > 0.0 && norm.isFinite()) { "记忆嵌入范数为零" }
}

private fun jsonInt(value: JsonElement): Int? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = primitive.content.toLongOrNull() ?: return null
    return number.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}
