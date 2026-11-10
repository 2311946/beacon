package me.rerere.rikkahub.data.datastore

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

class UsageBudgetTest {
    private val november = "2026-11"
    private fun state() = UsageBudgetState(november)

    @Test
    fun `直接使用API费用不根据token和缓存重复收费`() {
        val usage = TokenUsage(promptTokens = 1_000_000, completionTokens = 500_000, cachedTokens = 900_000, costYuan = "9")
        assertEquals(0, BigDecimal("9").compareTo(UsageBudgetCalculator.costYuan(usage)))
    }

    @Test
    fun `缺失费用不估算且免费请求是有效零`() {
        assertNull(UsageBudgetCalculator.costYuan(TokenUsage(100, 200)))
        assertNull(UsageBudgetCalculator.costYuan(null))
        assertEquals(0, UsageBudgetCalculator.costYuan(TokenUsage(costYuan = "0"))!!.signum())
    }

    @Test
    fun `小额请求不逐次舍入且请求重试各计一次`() {
        var current = state()
        repeat(1000) {
            current = UsageBudgetCalculator.record(current, TokenUsage(costYuan = "0.00000001"), november, november)
        }
        assertEquals(0, BigDecimal("0.00001").compareTo(BigDecimal(current.monthlyCosts.getValue(november))))
    }

    @Test
    fun `80和100百分比边界准确且超额继续累计`() {
        fun at(amount: String) = state().copy(monthlyCosts = mapOf(november to amount)).level
        assertEquals(UsageBudgetLevel.NORMAL, at("159.999999999"))
        assertEquals(UsageBudgetLevel.NEAR_LIMIT, at("160"))
        assertEquals(UsageBudgetLevel.NEAR_LIMIT, at("199.999999999"))
        assertEquals(UsageBudgetLevel.AT_LIMIT, at("200"))
        val over = UsageBudgetCalculator.record(state().copy(monthlyCosts = mapOf(november to "200")), TokenUsage(costYuan = "3"), november, november)
        assertEquals(203.0, over.spentYuan, 0.0)
        assertEquals(UsageBudgetLevel.AT_LIMIT, state().copy(budget = "0").level)
    }

    @Test
    fun `本地月初跨年闰年和时区正确`() {
        val instant = Instant.parse("2026-10-31T16:00:00Z").toEpochMilli()
        assertEquals("2026-11", UsageBudgetCalculator.monthAt(instant, ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-10", UsageBudgetCalculator.monthAt(instant, ZoneId.of("America/Los_Angeles")))
        listOf("2026-12-31T23:59:59Z", "2028-02-29T23:59:59Z").forEach {
            assertEquals(1000L, UsageBudgetCalculator.millisUntilNextMonth(Instant.parse(it).toEpochMilli(), ZoneId.of("UTC")))
        }
    }

    @Test
    fun `延迟旧月记账不覆盖新月不重算历史`() {
        var current = UsageBudgetCalculator.record(state(), TokenUsage(costYuan = "3"), november, november)
        current = current.copy(month = "2026-12")
        current = UsageBudgetCalculator.record(current, TokenUsage(costYuan = "4"), "2026-12", "2026-12")
        current = UsageBudgetCalculator.record(current, TokenUsage(costYuan = "4"), november, "2026-12")
        assertEquals("2026-12", current.month)
        assertEquals(4.0, current.spentYuan, 0.0)
        assertEquals(0, BigDecimal("7").compareTo(BigDecimal(current.monthlyCosts.getValue(november))))
    }

    @Test
    fun `非法费用拒绝且无费用不增加账本`() {
        listOf("NaN", "Infinity", "-1", "bad").forEach { invalid ->
            assertNull(UsageBudgetCalculator.costYuan(TokenUsage(costYuan = invalid)))
        }
        assertEquals(0.0, UsageBudgetCalculator.record(state(), TokenUsage(1000), november, november).spentYuan, 0.0)
    }

    @Test
    fun `旧价格配置被忽略但预算和账本保留`() = runBlocking {
        val prefs = MemoryPreferences().also {
            it.values["state"] = """{"month":"2026-11","budget":"300","monthlyCosts":{"2026-11":"123.45"},"prices":{"old":{"inputYuanPerMillion":999}}}"""
        }
        val store = store(prefs)
        assertEquals(123.45, store.stateFlow.value.spentYuan, 0.0)
        assertEquals(300.0, store.stateFlow.value.budgetYuan, 0.0)
        assertEquals("2026-12-01", store.stateFlow.value.resetDate)
        store.record(TokenUsage(1000000), november)
        assertEquals(123.45, store.stateFlow.value.spentYuan, 0.0)
    }

    @Test
    fun `并发请求与预算编辑不丢账且重启恢复`() = runBlocking {
        val prefs = MemoryPreferences()
        val store = store(prefs)
        (1..200).map {
            async(Dispatchers.Default) {
                store.record(TokenUsage(costYuan = "0.001"), november)
                if (it % 10 == 0) store.setBudgetYuan(it.toDouble())
            }
        }.awaitAll()
        assertEquals(0.2, store.stateFlow.value.spentYuan, 0.0)
        assertEquals(store.stateFlow.value, store(prefs).stateFlow.value)
    }

    @Test
    fun `前台月切换刷新归零保留价格预算且旧月可迟到`() = runBlocking {
        val prefs = MemoryPreferences()
        var now = Instant.parse("2026-11-30T23:59:59Z").toEpochMilli()
        val store = UsageBudgetStore(prefs.instance, { now }, { ZoneId.of("UTC") })
        store.record(TokenUsage(costYuan = "3"), november)
        now += 1000
        store.refreshForNow()
        assertEquals(0.0, store.stateFlow.value.spentYuan, 0.0)
        assertEquals(200.0, store.stateFlow.value.budgetYuan, 0.0)
        assertEquals("2027-01-01", store.stateFlow.value.resetDate)
        store.record(TokenUsage(costYuan = "3"), november)
        assertEquals("2026-12", store.stateFlow.value.month)
        assertEquals(0.0, store.stateFlow.value.spentYuan, 0.0)
    }

    @Test
    fun `提交失败不发布且损坏数据不覆盖`() = runBlocking {
        val prefs = MemoryPreferences()
        val store = store(prefs)
        val before = store.stateFlow.value
        prefs.succeeds = false
        try { store.setBudgetYuan(100.0); fail("应提交失败") } catch (_: java.io.IOException) { }
        assertEquals(before, store.stateFlow.value)
        assertNotNull(store.error.value)
        val corrupt = MemoryPreferences().also { it.values["state"] = "broken" }
        val restored = store(corrupt)
        assertNotNull(restored.error.value)
        try { restored.setBudgetYuan(100.0); fail("应拒绝覆盖") } catch (_: java.io.IOException) { }
        assertEquals("broken", corrupt.values["state"])
    }

    private fun store(prefs: MemoryPreferences) = UsageBudgetStore(
        prefs.instance, { Instant.parse("2026-11-15T12:00:00Z").toEpochMilli() }, { ZoneId.of("UTC") },
    )

    private class MemoryPreferences {
        val values = ConcurrentHashMap<String, String>()
        var succeeds = true
        val instance = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> {
                    val pending = mutableMapOf<String, String>()
                    lateinit var editor: SharedPreferences.Editor
                    editor = Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
                    ) { _, editMethod, editArgs ->
                        when (editMethod.name) {
                            "putString" -> { pending[editArgs!![0] as String] = editArgs[1] as String; editor }
                            "commit" -> { if (succeeds) values.putAll(pending); succeeds }
                            else -> error(editMethod.name)
                        }
                    } as SharedPreferences.Editor
                    editor
                }
                else -> error(method.name)
            }
        } as SharedPreferences
    }
}
