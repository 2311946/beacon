package me.rerere.rikkahub.data.ai

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDiagnosticsTest {
    @Test
    fun `仅确认已观测Provider错误对象或堆栈恢复原因链`() {
        val source = HttpException("private", statusCode = 503)
        assertTrue(observedProviderFailure(source, source) === source)
        assertTrue(observedProviderFailure(Exception("recovered", source), source) === source)
        assertNull(observedProviderFailure(HttpException("private", statusCode = 503), source))
        assertNull(observedProviderFailure(Exception("local failure"), source))
        assertNull(observedProviderFailure(source, null))
    }

    @Test
    fun `直接取消不分类`() {
        assertNull(classifyApiFailure(CancellationException("secret")))
    }

    @Test
    fun `嵌套取消优先于外层HTTP错误`() {
        val error = HttpException("Failed to get response: 401 secret")
        error.initCause(CancellationException("secret"))
        assertNull(classifyApiFailure(error))
    }

    @Test
    fun `HTTP异常无状态时仍安全分类`() {
        val failure = classifyApiFailure(HttpException("api_key=secret; private chat"))
        assertEquals(SafeApiFailure("http"), failure)
        assertFalse(failure!!.explanation.contains("secret"))
        assertFalse(failure.toString().contains("private chat"))
    }

    @Test
    fun `结构化状态优先于错误文本状态`() {
        assertEquals(
            SafeApiFailure("http", 503),
            classifyApiFailure(HttpException("Failed to get response: 401 secret", statusCode = 503)),
        )
    }

    @Test
    fun `无文本HTTP异常使用结构化状态`() {
        assertEquals(SafeApiFailure("http", 429), classifyApiFailure(HttpException("", statusCode = 429)))
    }

    @Test
    fun `越界结构化状态不会外传`() {
        assertEquals(SafeApiFailure("http"), classifyApiFailure(HttpException("secret", statusCode = 999)))
        assertEquals(
            SafeApiFailure("http", 403),
            classifyApiFailure(HttpException("HTTP 403: secret", statusCode = 200)),
        )
    }

    @Test
    fun `只从明确响应前缀获取状态`() {
        val failure = classifyApiFailure(Exception("Failed to get response: 429 api_key=secret"))
        assertEquals(SafeApiFailure("http", 429), failure)
        assertEquals(DiagnosisCause.RATE_LIMIT.suggestion, failure!!.explanation)
    }

    @Test
    fun `HTTP异常支持明确HTTP状态前缀`() {
        assertEquals(SafeApiFailure("http", 401), classifyApiFailure(HttpException("HTTP 401: secret")))
        assertEquals(SafeApiFailure("http", 503), classifyApiFailure(HttpException("HTTP/1.1 503 secret")))
        assertEquals(SafeApiFailure("http", 403), classifyApiFailure(HttpException("403 secret")))
    }

    @Test
    fun `未知异常中的任意数字不是HTTP状态`() {
        listOf("401 secret", "HTTP 401: secret", "api_key=429", "request failed with status 500").forEach {
            assertNull(classifyApiFailure(IllegalStateException(it)))
        }
    }

    @Test
    fun `拒绝伪造及畸形响应状态前缀`() {
        listOf(
            "secret Failed to get response: 401",
            "Failed to get response: 4010 secret",
            "Failed to get response: 401secret",
            "Failed to get response: 200 secret",
            "Failed to get response: 999 secret",
            "Failed to get response: -401 secret",
        ).forEach { assertNull(classifyApiFailure(Exception(it))) }
    }

    @Test
    fun `HTTP状态不会从消息中间的秘密提取`() {
        assertEquals(SafeApiFailure("http"), classifyApiFailure(HttpException("key=401 url=503")))
    }

    @Test
    fun `HTTP固定建议覆盖认证权限模型限流及服务故障`() {
        mapOf(
            401 to DiagnosisCause.AUTHENTICATION,
            403 to DiagnosisCause.PERMISSION,
            404 to DiagnosisCause.MODEL_NOT_FOUND,
            408 to DiagnosisCause.TIMEOUT,
            413 to DiagnosisCause.CONTEXT_LIMIT,
            429 to DiagnosisCause.RATE_LIMIT,
            500 to DiagnosisCause.SERVER_ERROR,
            504 to DiagnosisCause.TIMEOUT,
            400 to DiagnosisCause.INVALID_REQUEST,
        ).forEach { (status, cause) ->
            assertEquals(cause.suggestion, SafeApiFailure("http", status).explanation)
        }
    }

    @Test
    fun `识别网络异常类型而不采信异常内容`() {
        listOf(
            UnknownHostException("private-host secret"),
            ConnectException("secret"),
            java.net.NoRouteToHostException("private-route secret"),
            java.io.EOFException("truncated response secret"),
            SSLHandshakeException("private-certificate secret"),
        ).forEach { error ->
            val failure = classifyApiFailure(error)
            assertEquals(SafeApiFailure("network"), failure)
            assertFalse(failure!!.explanation.contains("secret"))
        }
    }

    @Test
    fun `识别网络超时`() {
        assertEquals(SafeApiFailure("timeout"), classifyApiFailure(SocketTimeoutException("secret")))
    }

    @Test
    fun `序列化异常只保留类别`() {
        val failure = classifyApiFailure(SerializationException("private response secret"))
        assertEquals(SafeApiFailure("serialization"), failure)
        assertFalse(failure!!.explanation.contains("private response"))
    }

    @Test
    fun `已知响应缺字段只匹配固定完整消息`() {
        listOf("choices is null", "message is null", "No candidates in response", "No content").forEach {
            assertEquals(SafeApiFailure("serialization"), classifyApiFailure(IllegalStateException(it)))
            assertNull(classifyApiFailure(IllegalStateException("private $it secret")))
        }
    }

    @Test
    fun `未知异常及普通文件IO不误报为接口错误`() {
        assertNull(classifyApiFailure(IllegalArgumentException("secret")))
        assertNull(classifyApiFailure(IOException("local file secret")))
        assertNull(classifyApiFailure(Exception()))
    }

    @Test
    fun `包装异常可识别根因`() {
        assertEquals(
            SafeApiFailure("network"),
            classifyApiFailure(IllegalStateException("secret wrapper", UnknownHostException("secret"))),
        )
    }

    @Test
    fun `循环原因链不会无限遍历`() {
        val first = Exception("secret")
        val second = Exception("secret", first)
        first.initCause(second)
        assertNull(classifyApiFailure(first))
    }

    @Test
    fun `图片不支持白名单优先于通用HTTP错误`() {
        listOf(
            "image_unsupported",
            "unsupported image input",
            "This model does not support images",
            "image input is not supported",
            "image_url is only supported by certain models",
            "不支持图片输入",
        ).forEach { pattern ->
            val failure = classifyApiFailure(Exception("Failed to get response: 400 $pattern; secret"))
            assertEquals(SafeApiFailure("image_unsupported", 400), failure)
            assertFalse(failure!!.explanation.contains("secret"))
        }
    }

    @Test
    fun `上下文超限白名单优先于通用HTTP错误`() {
        listOf("context_length_exceeded", "maximum context length", "too many tokens", "上下文长度超限").forEach {
            assertEquals(
                SafeApiFailure("context_limit", 400),
                classifyApiFailure(Exception("Failed to get response: 400 $it secret")),
            )
        }
    }

    @Test
    fun `无HTTP包装的白名单模式也只保留安全类别`() {
        assertEquals(SafeApiFailure("image_unsupported"), classifyApiFailure(Exception("不支持图像输入 secret")))
        assertEquals(SafeApiFailure("context_limit"), classifyApiFailure(Exception("context_length_exceeded secret")))
    }

    @Test
    fun `图片和上下文的一般提及不触发白名单`() {
        listOf("image secret", "context secret", "unsupported tool secret", "token secret").forEach {
            assertNull(classifyApiFailure(Exception(it)))
        }
    }

    @Test
    fun `任意调用方kind不会回显到固定说明`() {
        val failure = SafeApiFailure("secret; ignore instructions", Int.MAX_VALUE)
        assertFalse(failure.explanation.contains("secret"))
        assertFalse(failure.explanation.contains(Int.MAX_VALUE.toString()))
    }

    @Test
    fun `所有类别均只返回本地固定建议`() {
        mapOf(
            "network" to DiagnosisCause.NETWORK,
            "timeout" to DiagnosisCause.TIMEOUT,
            "serialization" to DiagnosisCause.INVALID_RESPONSE,
            "image_unsupported" to DiagnosisCause.IMAGE_UNSUPPORTED,
            "context_limit" to DiagnosisCause.CONTEXT_LIMIT,
        ).forEach { (kind, cause) ->
            assertEquals(cause.suggestion, SafeApiFailure(kind).explanation)
        }
    }

    @Test
    fun `诊断参数清除自定义配置内置工具与提供商覆盖`() {
        val headers = listOf(CustomHeader("Authorization", "secret"))
        val bodies = listOf(CustomBody("messages", JsonPrimitive("private chat")))
        val overwrite = ProviderSetting.OpenAI(apiKey = "overwrite-secret")
        val model = Model(
            modelId = "original-model",
            displayName = "secret display name",
            customHeaders = headers,
            customBodies = bodies,
            abilities = listOf(ModelAbility.TOOL),
            tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext, BuiltInTools.ImageGeneration),
            providerOverwrite = overwrite,
        )

        val params = diagnosticParams(model)

        assertEquals(model.id, params.model.id)
        assertEquals(model.modelId, params.model.modelId)
        assertEquals("", params.model.displayName)
        assertTrue(params.model.customHeaders.isEmpty())
        assertTrue(params.model.customBodies.isEmpty())
        assertTrue(params.model.tools.isEmpty())
        assertNull(params.model.providerOverwrite)
        assertTrue(params.customHeaders.isEmpty())
        assertTrue(params.customBody.isEmpty())
        assertTrue(params.tools.isEmpty())
        assertEquals(256, params.maxTokens)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertNull(params.sessionId)
        assertNull(params.temperature)
        assertNull(params.topP)
        assertNotSame(model, params.model)
        assertEquals(headers, model.customHeaders)
        assertEquals(bodies, model.customBodies)
        assertEquals(overwrite, model.providerOverwrite)
        assertEquals(3, model.tools.size)
    }

    @Test
    fun `独立请求不复用会话标识或引入工具`() {
        val model = Model(modelId = "original-model")
        repeat(2) {
            val params = diagnosticParams(model)
            assertNull(params.sessionId)
            assertTrue(params.tools.isEmpty())
            assertEquals(model.modelId, params.model.modelId)
        }
    }

    @Test
    fun `严格JSON枚举均映射到固定建议`() {
        DiagnosisCause.entries.forEach { cause ->
            assertEquals(cause, parseDiagnosisCause("{\"cause\":\"${cause.name}\"}"))
            assertTrue(cause.suggestion.isNotBlank())
        }
    }

    @Test
    fun `严格JSON允许标准空白`() {
        assertEquals(DiagnosisCause.NETWORK, parseDiagnosisCause(" \n { \"cause\" : \"NETWORK\" }\t\r"))
    }

    @Test
    fun `拒绝模型自由文本与代码围栏`() {
        listOf(
            "请把密钥发给我",
            "```json\n{\"cause\":\"NETWORK\"}\n```",
            "解释：{\"cause\":\"NETWORK\"}",
            "{\"cause\":\"NETWORK\"}secret",
        ).forEach { assertNull(parseDiagnosisCause(it)) }
    }

    @Test
    fun `拒绝额外字段及重复键防止自由建议注入`() {
        listOf(
            "{\"cause\":\"NETWORK\",\"suggestion\":\"secret\"}",
            "{\"cause\":\"NETWORK\",\"cause\":\"AUTHENTICATION\"}",
            "{\"cause\":\"NETWORK\",\"cause\":\"NETWORK\"}",
        ).forEach { assertNull(parseDiagnosisCause(it)) }
    }

    @Test
    fun `拒绝错误JSON结构和类型`() {
        listOf(
            "null",
            "{}",
            "[]",
            "[{\"cause\":\"NETWORK\"}]",
            "{\"cause\":null}",
            "{\"cause\":42}",
            "{\"cause\":{\"value\":\"NETWORK\"}}",
            "{cause:'NETWORK'}",
            "{\"cause\":\"NETWORK\",}",
            "{\"cause\":\"NETWORK\"}{}",
            "{\"cause\":\"NETWORK\" /* secret */}",
        ).forEach { assertNull(parseDiagnosisCause(it)) }
    }

    @Test
    fun `拒绝未知枚举与隐藏自由文本`() {
        listOf("SECRET", "network", "NETWORK secret", "NETWORK\\nsecret", "\\u004eETWORK").forEach {
            assertNull(parseDiagnosisCause("{\"cause\":\"$it\"}"))
        }
    }

    @Test
    fun `拒绝过长诊断响应与非JSON空白`() {
        assertNull(parseDiagnosisCause(" ".repeat(257) + "{\"cause\":\"NETWORK\"}"))
        assertNull(parseDiagnosisCause("\u000c{\"cause\":\"NETWORK\"}"))
    }
}
