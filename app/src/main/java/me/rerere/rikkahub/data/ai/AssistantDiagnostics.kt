package me.rerere.rikkahub.data.ai

import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.NoRouteToHostException
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import kotlin.uuid.Uuid

private const val DIAGNOSTIC_TIMEOUT_MS = 10_000L
private const val PROBE_SUCCESS = "简化纯文本请求已收到响应；本次移除了自定义请求头和请求体，不代表原聊天完整配置、图片输入或工具调用可用。"
private const val PROBE_FAILED = "未能确认纯文本接口可用，请检查模型与服务配置。"
private val failureKinds = setOf("http", "network", "timeout", "serialization", "image_unsupported", "context_limit")
private val responseShapeMessages = setOf("choices is null", "message is null", "No candidates in response", "No content")
private val responseStatusPrefix = Regex("^Failed to get response: ([45][0-9]{2})(?=\\s|$)")
private val httpStatusPrefix = Regex("^(?:HTTP(?:/[0-9.]+)? )?([45][0-9]{2})(?=\\s|:|$)")
private val imageUnsupportedPattern = Regex(
    "\\b(?:image_unsupported|unsupported_image|unsupported image input|" +
        "(?:does not|doesn't) support images?|image inputs? (?:is|are) not supported|" +
        "image_url is only supported by certain models)\\b|不支持(?:图片|图像)(?:输入)?",
    RegexOption.IGNORE_CASE,
)
private val contextLimitPattern = Regex(
    "\\b(?:context_length_exceeded|maximum context length|context (?:length|window) exceeded|" +
        "too many tokens)\\b|上下文(?:长度)?(?:超限|超出限制|超过限制)",
    RegexOption.IGNORE_CASE,
)

/** 仅保留本地白名单类别和状态码，不持有原始异常或服务端内容。 */
data class SafeApiFailure(val kind: String, val httpStatus: Int? = null) {
    val explanation: String
        get() = when (kind) {
            "http" -> when (httpStatus) {
                401 -> DiagnosisCause.AUTHENTICATION.suggestion
                403 -> DiagnosisCause.PERMISSION.suggestion
                404 -> DiagnosisCause.MODEL_NOT_FOUND.suggestion
                408, 504 -> DiagnosisCause.TIMEOUT.suggestion
                413 -> DiagnosisCause.CONTEXT_LIMIT.suggestion
                429 -> DiagnosisCause.RATE_LIMIT.suggestion
                in 500..599 -> DiagnosisCause.SERVER_ERROR.suggestion
                else -> DiagnosisCause.INVALID_REQUEST.suggestion
            }
            "network" -> DiagnosisCause.NETWORK.suggestion
            "timeout" -> DiagnosisCause.TIMEOUT.suggestion
            "serialization" -> DiagnosisCause.INVALID_RESPONSE.suggestion
            "image_unsupported" -> DiagnosisCause.IMAGE_UNSUPPORTED.suggestion
            "context_limit" -> DiagnosisCause.CONTEXT_LIMIT.suggestion
            else -> PROBE_FAILED
        }
}

fun classifyApiFailure(error: Throwable): SafeApiFailure? {
    val chain = failureChain(error)
    if (chain.any { it is CancellationException }) return null
    for (cause in chain) {
        // 原始内容只用于本地白名单匹配，绝不进入结果、提示词或日志。
        val message = cause.message.orEmpty().take(8192)
        val status = (cause as? HttpException)?.statusCode?.takeIf { it in 400..599 }
            ?: responseStatusPrefix.find(message)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (cause is HttpException) {
                httpStatusPrefix.find(message)?.groupValues?.get(1)?.toIntOrNull()
            } else {
                null
            }
        when {
            imageUnsupportedPattern.containsMatchIn(message) -> return SafeApiFailure("image_unsupported", status)
            contextLimitPattern.containsMatchIn(message) -> return SafeApiFailure("context_limit", status)
            cause is HttpException || status != null -> return SafeApiFailure("http", status)
            cause is SocketTimeoutException || cause is InterruptedIOException -> return SafeApiFailure("timeout")
            cause is UnknownHostException || cause is SocketException || cause is SSLException ||
                cause is ProtocolException || cause is NoRouteToHostException || cause is EOFException -> return SafeApiFailure("network")
            cause is SerializationException || message in responseShapeMessages -> return SafeApiFailure("serialization")
        }
    }
    return null
}

// flowOn 的协程堆栈恢复可能复制异常，并把原异常放进 cause；仍须按对象身份确认 Provider 来源。
internal fun observedProviderFailure(error: Throwable, observed: Throwable?): Throwable? =
    observed?.takeIf { source -> failureChain(error).any { it === source } }

private fun failureChain(error: Throwable): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val chain = mutableListOf<Throwable>()
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        chain += current
        current = current.cause
    }
    return chain
}

internal fun diagnosticParams(model: Model): TextGenerationParams = TextGenerationParams(
    model = model.copy(
        displayName = "",
        customHeaders = emptyList(),
        customBodies = emptyList(),
        tools = emptySet(),
        providerOverwrite = null,
    ),
    maxTokens = 256,
    sessionId = null,
)

internal enum class DiagnosisCause(val suggestion: String) {
    AUTHENTICATION("服务认证失败，请检查凭据是否有效；不要在聊天中发送密钥。"),
    PERMISSION("服务拒绝访问，请检查账号权限及模型授权。"),
    MODEL_NOT_FOUND("未找到请求的模型或接口，请检查模型标识及服务地址。"),
    RATE_LIMIT("服务请求受限，请稍后手动重试并检查额度。"),
    SERVER_ERROR("服务暂时异常，请稍后手动重试或检查服务状态。"),
    NETWORK("网络连接失败，请检查网络、代理及服务地址。"),
    TIMEOUT("请求超时，请检查网络与服务响应情况。"),
    INVALID_RESPONSE("服务响应格式无法解析，请检查接口兼容性。"),
    IMAGE_UNSUPPORTED("接口可能不支持图片输入，请改用纯文本或支持图片的模型。"),
    CONTEXT_LIMIT("请求可能超过上下文限制，请减少历史消息或输入长度。"),
    INVALID_REQUEST("服务拒绝了请求，请检查模型及接口配置。"),
    UNKNOWN("暂时无法确定原因，请检查模型、网络与服务配置。"),
}

// 仅接受单个 cause 字符串字段的 JSON 子集，拒绝重复键、额外字段、代码围栏和自由文本。
private val diagnosisJson = Regex("""[ \t\r\n]*\{[ \t\r\n]*"cause"[ \t\r\n]*:[ \t\r\n]*"([A-Z_]+)"[ \t\r\n]*\}[ \t\r\n]*""")

internal fun parseDiagnosisCause(text: String): DiagnosisCause? {
    if (text.length > 256) return null
    val name = diagnosisJson.matchEntire(text)?.groupValues?.get(1) ?: return null
    return DiagnosisCause.entries.firstOrNull { it.name == name }
}

/** 必须注入专用的有限时、无请求日志且禁用传输重试的 ProviderManager。 */
class AssistantDiagnostics(
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
) {
    suspend fun explain(
        failure: SafeApiFailure,
        model: Model,
        provider: ProviderSetting,
        assistantId: Uuid,
        conversationId: Uuid,
    ): String? {
        currentCoroutineContext().ensureActive()
        // SafeApiFailure 的构造函数公开，不能信任调用方传入的 kind。
        if (failure.kind !in failureKinds) return null
        val safeStatus = failure.httpStatus?.takeIf { it in 400..599 }
        return try {
            withTimeoutOrNull(DIAGNOSTIC_TIMEOUT_MS) {
                val text = request(
                    model = model,
                    provider = provider,
                    tag = UsageTag(UsageSource.ERROR_DIAGNOSIS, assistantId, conversationId),
                    messages = listOf(
                        UIMessage.system(
                            "仅根据错误类别和状态码选择可能原因，不推断聊天内容。" +
                                "只返回严格 JSON：{\"cause\":\"枚举值\"}，不得添加其他字段或文字。" +
                                "cause 仅允许：" + DiagnosisCause.entries.joinToString(",") { it.name } +
                                "。信息不足时选择 UNKNOWN。",
                        ),
                        UIMessage.user("kind=${failure.kind};status=${safeStatus ?: "unknown"}"),
                    ),
                )
                parseDiagnosisCause(text)?.suggestion
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            failureChain(error).filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
            null
        }
    }

    suspend fun probe(
        model: Model,
        provider: ProviderSetting,
        assistantId: Uuid,
        conversationId: Uuid,
    ): String {
        currentCoroutineContext().ensureActive()
        return try {
            withTimeoutOrNull(DIAGNOSTIC_TIMEOUT_MS) {
                val text = request(
                    model = model,
                    provider = provider,
                    tag = UsageTag(UsageSource.HEALTH_CHECK, assistantId, conversationId),
                    messages = listOf(UIMessage.user("这是独立的纯文本连通性检查，请只回复 OK。")),
                )
                if (text.isNotBlank()) PROBE_SUCCESS else SafeApiFailure("serialization").explanation
            } ?: SafeApiFailure("timeout").explanation
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            failureChain(error).filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
            classifyApiFailure(error)?.explanation ?: PROBE_FAILED
        }
    }

    private suspend fun request(
        model: Model,
        provider: ProviderSetting,
        tag: UsageTag,
        messages: List<UIMessage>,
    ): String {
        val setting = provider.copyProvider(customHeaders = emptyList(), models = emptyList())
        return usageRecorder.wrap(providerManager.getProviderByType(setting), tag).generateText(
            providerSetting = setting,
            messages = messages,
            params = diagnosticParams(model),
        ).message.toText()
    }
}
