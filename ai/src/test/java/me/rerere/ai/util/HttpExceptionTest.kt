package me.rerere.ai.util

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpExceptionTest {
    @Test
    fun `旧单参数构造保留消息和异常类型且状态未知`() {
        val exception: RuntimeException = HttpException("original message")

        assertEquals("original message", exception.message)
        assertTrue(exception is HttpException)
        assertNull((exception as HttpException).statusCode)
    }

    @Test
    fun `显式状态码不改变原始消息`() {
        val exception = HttpException("Failed to get response: 429 original body", statusCode = 429)

        assertEquals("Failed to get response: 429 original body", exception.message)
        assertEquals(429, exception.statusCode)
    }

    @Test
    fun `原错误解析保留消息且不从响应体推断HTTP状态`() {
        val exception = Json.parseToJsonElement(
            """{"error":{"message":"original error","code":429,"status":"RESOURCE_EXHAUSTED"}}"""
        ).parseErrorDetail()

        assertEquals("original error", exception.message)
        assertNull(exception.statusCode)
    }
}
