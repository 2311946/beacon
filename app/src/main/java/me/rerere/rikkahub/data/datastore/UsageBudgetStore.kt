package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.TokenUsage
import java.io.IOException
import java.math.BigDecimal
import java.time.ZoneId

/** 进程内由 Koin 单例持有；所有读改写串行提交整个快照，成功后才发布。 */
class UsageBudgetStore internal constructor(
    private val preferences: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        context.applicationContext.getSharedPreferences("usage_budget", Context.MODE_PRIVATE),
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshForNow()
                    // 前台持续停留也跨月刷新；每分钟重算以响应时区或时钟修改。
                    delay(UsageBudgetCalculator.millisUntilNextMonth(clock(), zone()).coerceAtMost(60_000))
                }
            }
        }
    }

    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var writeFailure: Exception? = null
    private val mutableError = MutableStateFlow<String?>(null)
    private val state = MutableStateFlow(readState())
    val stateFlow: StateFlow<UsageBudgetState> = state.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()

    suspend fun refreshForNow() = update { it.copy(month = monthNow()) }

    suspend fun setBudgetYuan(yuan: Double) {
        require(yuan.isFinite() && yuan >= 0) { "月预算必须是有限的非负数字" }
        update { it.copy(month = monthNow(), budget = BigDecimal.valueOf(yuan).toPlainString()) }
    }

    /** 所有来源、后台、重试均计入；按 record 入口捕获的完成时间和本地时区归月。 */
    suspend fun record(
        usage: TokenUsage,
        recordedMonth: String,
    ) = update {
        UsageBudgetCalculator.record(it, usage, recordedMonth, monthNow())
    }

    private suspend fun update(transform: (UsageBudgetState) -> UsageBudgetState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (writeFailure != null) throw IOException(mutableError.value, writeFailure)
            val next = transform(state.value)
            if (next == state.value) return@withLock
            try {
                if (!preferences.edit().putString(DATA_KEY, json.encodeToString(next)).commit()) {
                    throw IOException("月用量预算写入失败")
                }
            } catch (exception: Exception) {
                writeFailure = exception
                mutableError.value = "预算保存失败，用量可能不完整。请检查存储空间后重启应用。"
                throw IOException(mutableError.value, exception)
            }
            state.value = next
        }
    }

    private fun readState(): UsageBudgetState {
        val month = monthNow()
        return try {
            val raw = preferences.getString(DATA_KEY, null)
            val restored = if (raw == null) UsageBudgetState(month) else json.decodeFromString<UsageBudgetState>(raw)
            require(BigDecimal(restored.budget).signum() >= 0)
            restored.monthlyCosts.forEach { (key, amount) ->
                java.time.YearMonth.parse(key)
                require(BigDecimal(amount).signum() >= 0)
            }
            restored.copy(month = month)
        } catch (exception: Exception) {
            // 保留原始数据，读取失败不能用默认值覆盖已有账本。
            writeFailure = exception
            mutableError.value = "预算数据读取失败，已保留原数据并停止写入，本月金额不可用。"
            UsageBudgetState(month)
        }
    }

    private fun monthNow() = UsageBudgetCalculator.monthAt(clock(), zone())

    companion object {
        private const val DATA_KEY = "state"
        fun recordedMonth(nowMillis: Long): String = UsageBudgetCalculator.monthAt(nowMillis, ZoneId.systemDefault())
    }
}
