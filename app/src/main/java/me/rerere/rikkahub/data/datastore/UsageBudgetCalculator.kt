package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import me.rerere.ai.core.TokenUsage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

enum class UsageBudgetLevel { NORMAL, NEAR_LIMIT, AT_LIMIT }

@Serializable
data class UsageBudgetState(
    val month: String,
    val budget: String = "200",
    val monthlyCosts: Map<String, String> = emptyMap(),
) {
    val budgetYuan: Double get() = budget.toDouble()
    val spentYuan: Double get() = (monthlyCosts[month] ?: "0").toDouble()
    val resetDate: String get() = YearMonth.parse(month).plusMonths(1).atDay(1).toString()
    val level: UsageBudgetLevel get() {
        val limit = BigDecimal(budget)
        val spent = BigDecimal(monthlyCosts[month] ?: "0")
        return when {
            spent >= limit -> UsageBudgetLevel.AT_LIMIT
            spent >= limit.multiply(BigDecimal("0.8")) -> UsageBudgetLevel.NEAR_LIMIT
            else -> UsageBudgetLevel.NORMAL
        }
    }
}

internal object UsageBudgetCalculator {
    fun monthAt(nowMillis: Long, zone: ZoneId): String =
        YearMonth.from(Instant.ofEpochMilli(nowMillis).atZone(zone)).toString()

    fun millisUntilNextMonth(nowMillis: Long, zone: ZoneId): Long {
        val next = YearMonth.parse(monthAt(nowMillis, zone)).plusMonths(1)
            .atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return (next - nowMillis).coerceAtLeast(1)
    }

    fun costYuan(usage: TokenUsage?): BigDecimal? = usage?.costYuan
        ?.takeIf { it.length <= 128 }?.toBigDecimalOrNull()
        ?.takeIf { it.signum() >= 0 && it.precision() <= 40 && it.scale() in -20..40 }

    fun record(
        state: UsageBudgetState,
        usage: TokenUsage?,
        recordedMonth: String,
        currentMonth: String,
    ): UsageBudgetState {
        val refreshed = state.copy(month = currentMonth)
        val cost = costYuan(usage) ?: return refreshed
        if (cost.signum() == 0) return refreshed
        val total = BigDecimal(state.monthlyCosts[recordedMonth] ?: "0").add(cost)
        return refreshed.copy(monthlyCosts = state.monthlyCosts + (recordedMonth to total.toPlainString()))
    }
}

fun formatBudgetYuan(value: Double): String = BigDecimal.valueOf(value)
    .setScale(2, RoundingMode.HALF_UP).toPlainString()
