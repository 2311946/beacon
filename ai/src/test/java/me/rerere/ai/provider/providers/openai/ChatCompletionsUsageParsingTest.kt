package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.core.sum
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChatCompletionsAPI token usage parsing.
 */
class ChatCompletionsUsageParsingTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    // Helper to invoke private parseTokenUsage via reflection
    private fun parseTokenUsage(usage: JsonObject): TokenUsage? {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "parseTokenUsage",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(api, usage) as TokenUsage?
    }

    @Test fun `API direct cost priority zero and currencies`() {
        fun cost(raw: String) = parseTokenUsage(Json.parseToJsonElement(raw).jsonObject)?.costYuan
        assertEquals("0.125", cost("""{"cost":0.125}"""))
        assertEquals("2.50", cost("""{"total_cost":"2.50","cost":8,"currency":"CNY"}"""))
        assertEquals("0", cost("""{"total_cost":0,"cost":8}"""))
        assertEquals(null, cost("""{"cost":1,"currency":"USD"}"""))
        for (raw in listOf("{}", "{\"cost\":-1}", "{\"cost\":\"NaN\"}", "{\"cost\":{}}")) assertEquals(null, cost(raw))
    }

    @Test fun `API unit prices use declared units and require full information`() {
        fun cost(raw: String) = parseTokenUsage(Json.parseToJsonElement(raw).jsonObject)?.costYuan?.toBigDecimal()
        assertEquals(0, java.math.BigDecimal("0.4").compareTo(cost("""{"prompt_tokens":100,"completion_tokens":50,"prompt_unit_price":"0.002","completion_unit_price":"0.004"}""")))
        assertEquals(0, java.math.BigDecimal("0.4").compareTo(cost("""{"input_tokens":100000,"output_tokens":50000,"input_unit_price":2,"output_unit_price":4,"price_unit":"per_million_tokens"}""")))
        assertEquals(null, cost("""{"prompt_tokens":100,"completion_tokens":50,"prompt_unit_price":2}"""))
        assertEquals(null, cost("""{"prompt_tokens":100,"completion_tokens":50,"prompt_unit_price":2,"completion_unit_price":4,"price_unit":"unknown"}"""))
    }

    @Test fun `cost snapshots merge while independent requests sum`() {
        val one = TokenUsage(costYuan = "0.1")
        val two = TokenUsage(costYuan = "0.2")
        assertEquals("0.2", one.merge(two).merge(two).costYuan)
        assertEquals("0", one.merge(TokenUsage(costYuan = "0")).costYuan)
        assertEquals("0.1", one.merge(TokenUsage()).costYuan)
        assertEquals("0.3", one.sum(two)?.costYuan)
        assertEquals(one, Json.decodeFromString<TokenUsage>(Json.encodeToString(TokenUsage.serializer(), one)))
        assertEquals(null, Json.decodeFromString<TokenUsage>("{}").costYuan)
    }

    // #1576: cached tokens 按 provider 方言兜底解析
    @Test
    fun `cached tokens fall back across provider dialects`() {
        fun usage(jsonStr: String) = parseTokenUsage(Json.parseToJsonElement(jsonStr).jsonObject)

        // OpenAI 官方嵌套格式
        assertEquals(
            12,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_tokens_details":{"cached_tokens":12}}""")?.cachedTokens
        )
        // Moonshot 顶层 cached_tokens
        assertEquals(
            7,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"cached_tokens":7}""")?.cachedTokens
        )
        // DeepSeek 顶层 prompt_cache_hit_tokens
        assertEquals(
            5,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_cache_hit_tokens":5,"prompt_cache_miss_tokens":3}""")?.cachedTokens
        )
        // 嵌套字段优先于顶层兜底
        assertEquals(
            12,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_tokens_details":{"cached_tokens":12},"cached_tokens":7}""")?.cachedTokens
        )
        // 都没有时为 0
        assertEquals(
            0,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}""")?.cachedTokens
        )
    }
}
