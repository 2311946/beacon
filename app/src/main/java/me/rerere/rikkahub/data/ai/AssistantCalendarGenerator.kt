package me.rerere.rikkahub.data.ai

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.AssistantCalendarEntry
import me.rerere.rikkahub.data.model.parseCalendarTime
import me.rerere.rikkahub.data.model.validateCalendarEntries
import me.rerere.rikkahub.data.repository.AssistantCalendarRepository
import kotlin.uuid.Uuid

/** Independent background request: no conversation writes, tools, status updates or notifications. */
class AssistantCalendarGenerator(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val repository: AssistantCalendarRepository,
) {
    private val locks = ConcurrentHashMap<Uuid, Mutex>()
    private val concurrency = Semaphore(2)

    private suspend fun isCalendarEnabled(assistantId: Uuid): Boolean =
        settingsStore.awaitLoaded().assistants.firstOrNull { it.id == assistantId }?.enableCalendar == true

    suspend fun ensureDay(assistantId: Uuid, date: LocalDate) = withContext(Dispatchers.IO) {
        locks.getOrPut(assistantId) { Mutex() }.withLock {
            // 关闭时连日常模板也不复制，已有日程仍可读取。
            if (!isCalendarEnabled(assistantId)) return@withLock
            if (!repository.materializeDay(assistantId, date)) {
                generate(assistantId, date, createTemplate = true)
            }
        }
    }

    suspend fun regenerateDay(assistantId: Uuid, date: LocalDate) = withContext(Dispatchers.IO) {
        locks.getOrPut(assistantId) { Mutex() }.withLock {
            check(isCalendarEnabled(assistantId)) { "请先在角色设置中开启「日历行程」" }
            generate(assistantId, date, createTemplate = false)
        }
    }

    private suspend fun generate(assistantId: Uuid, date: LocalDate, createTemplate: Boolean) {
        val revision = repository.getDay(assistantId, date)?.revision
        val entries = try {
            concurrency.withPermit {
                withTimeout(120_000L) {
                    val settings = settingsStore.awaitLoaded()
                    val assistant = settings.assistants.firstOrNull { it.id == assistantId }
                        ?: error("角色已不存在")
                    // 等待并发名额期间开关可能已关闭，使用最新角色配置复查。
                    check(assistant.enableCalendar) { "请先在角色设置中开启「日历行程」" }
                    val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                        ?.takeIf { it.modelId.isNotBlank() && it.type == ModelType.CHAT && Modality.TEXT in it.outputModalities }
                        ?: error("请先为角色配置可用的聊天模型")
                    val provider = model.findProvider(settings.providers)?.takeIf { it.enabled }
                        ?: error("角色模型的提供商不可用")
                    val reference = "角色：${assistant.name}\n人设参考：${assistant.systemPrompt}\n" +
                        "日期：$date（${date.dayOfWeek}），时区：${ZoneId.systemDefault().id}。\n" +
                        "请根据角色人设与世界背景，制定这一天的生活计划，涵盖作息、工作或学习及休闲。" +
                        "这是计划，不是已经发生的活动。"
                    val messages = listOf(
                        UIMessage.system(assistant.systemPrompt),
                        UIMessage.user(reference),
                    ).transforms(
                        transformers = listOf(PromptInjectionTransformer, PlaceholderTransformer),
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings,
                    ) + UIMessage.user(
                        "本次只生成日历数据，不进行聊天或调用工具。返回且仅返回 JSON：" +
                            "{\"entries\":[{\"start\":\"08:00\",\"end\":\"09:00\",\"content\":\"计划内容\"}]}。" +
                            "生成 6–16 个合理的不重叠时间段，按时间排序，均在当天内。" +
                            "时间使用24小时制 HH:mm，结束可为24:00；每项内容不超过500字符。" +
                            "不要代码块、旁白或额外字段。日程描述须符合角色设定。"
                    )
                    val result = usageRecorder.wrap(
                        providerManager.getProviderByType(provider),
                        UsageTag(UsageSource.CALENDAR, assistantId),
                    ).generateText(
                        providerSetting = provider,
                        messages = messages,
                        params = backgroundTextGenerationParams(
                            model.copy(tools = emptySet()), Uuid.random(), ReasoningLevel.OFF,
                        ).copy(
                            maxTokens = 4096,
                            tools = emptyList(),
                            customHeaders = assistant.customHeaders + model.customHeaders,
                            customBody = assistant.customBodies + model.customBodies,
                        ),
                    )
                    parseCalendarEntries(result.message.toText())
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Only the local request timeout becomes an error; parent cancellation still propagates.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            throw IllegalStateException("AI 生成日程超时，请重试", e)
        }
        // 请求过程中关闭开关，不将晚到的 AI 结果写入日程或模板。
        check(isCalendarEnabled(assistantId)) { "该角色的「日历行程」已关闭，本次生成未保存" }
        check(repository.commitGeneratedDay(assistantId, date, entries, revision, createTemplate)) {
            "日程已被其他操作修改，本次生成未覆盖现有计划，请重新查看"
        }
    }
}

@Serializable
private data class CalendarResponse(val entries: List<CalendarResponseEntry>)

@Serializable
private data class CalendarResponseEntry(val start: String, val end: String, val content: String)

internal fun parseCalendarEntries(raw: String): List<AssistantCalendarEntry> {
    val cleaned = raw.replace(Regex("(?is)<think>.*?</think>"), "").trim()
    val text = if (cleaned.startsWith("```")) {
        require(cleaned.endsWith("```")) { "AI 返回的日程数据不完整，请重试" }
        cleaned.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    } else cleaned
    val response = Json.decodeFromString<CalendarResponse>(text)
    require(response.entries.isNotEmpty()) { "AI 返回了空日程，请重试" }
    return validateCalendarEntries(response.entries.map {
        AssistantCalendarEntry(parseCalendarTime(it.start), parseCalendarTime(it.end, true), it.content)
    })
}
