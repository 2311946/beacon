package me.rerere.ai.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.util.Locale

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
    // API 报告的费用；null 表示未提供/无法识别，0 是有效的免费请求。
    val costYuan: String? = null,
)

/**
 * 合并同一次请求内的 usage 快照：各字段取最新的非零值。
 * 跨请求（如多轮工具调用的各个步骤）累计请用 [sum]。
 */
fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    val cachedTokens = if (other.cachedTokens > 0) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens,
        costYuan = other.costYuan ?: this?.costYuan,
    )
}

/**
 * 累加多次请求的 usage，每次请求都会按完整上下文计费，因此 prompt 也直接相加。
 */
fun TokenUsage?.sum(other: TokenUsage?): TokenUsage? {
    if (this == null) return other
    if (other == null) return this
    return TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        totalTokens = totalTokens + other.totalTokens,
        costYuan = if (costYuan == null && other.costYuan == null) null else
            (BigDecimal(costYuan ?: "0") + BigDecimal(other.costYuan ?: "0")).toPlainString(),
    )
}

/** 未标币种按站子账户元处理；明确外币不混入人民币预算。不推测未知单位。 */
fun apiUsageCostYuan(usage: JsonObject): String? {
    fun text(key: String) = (usage[key] as? JsonPrimitive)?.content
    fun decimal(key: String): BigDecimal? = text(key)?.takeIf { it.length <= 128 }
        ?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.precision() <= 40 && it.scale() in -20..40 }
    for (key in listOf("currency", "cost_currency")) {
        if (usage.containsKey(key) && text(key)?.trim()?.uppercase(Locale.ROOT) !in setOf("CNY", "RMB", "元", "人民币", "¥", "￥")) return null
    }
    // 总额优先，存在但无效则不以分项猜测替代。
    for (key in listOf("total_cost", "cost")) {
        if (usage.containsKey(key)) return decimal(key)?.toPlainString()
    }
    fun component(prefix: String, alternate: String): BigDecimal? {
        val tokenKey = listOf("${prefix}_tokens", "${alternate}_tokens").firstOrNull { usage.containsKey(it) }
            ?: return null
        val tokens = decimal(tokenKey) ?: return null
        if (tokens.signum() == 0) return BigDecimal.ZERO
        val priceKey = listOf("${prefix}_unit_price", "${alternate}_unit_price").firstOrNull { usage.containsKey(it) }
            ?: return null
        val price = decimal(priceKey) ?: return null
        val unitKey = listOf("${prefix}_price_unit", "${alternate}_price_unit", "price_unit").firstOrNull { usage.containsKey(it) }
        val divisor = when (unitKey?.let(::text)?.lowercase(Locale.ROOT)) {
            null -> if (unitKey == null) BigDecimal.ONE else return null
            "token", "per_token", "1" -> BigDecimal.ONE
            "1k", "1000", "per_1k_tokens", "per_1000_tokens" -> BigDecimal("1000")
            "1m", "1000000", "per_million_tokens", "per_1m_tokens" -> BigDecimal("1000000")
            else -> return null
        }
        return tokens.multiply(price).divide(divisor)
    }
    val input = component("prompt", "input") ?: return null
    val output = component("completion", "output") ?: return null
    return input.add(output).toPlainString()
}
