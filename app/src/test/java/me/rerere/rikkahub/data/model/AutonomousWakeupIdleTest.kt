package me.rerere.rikkahub.data.model

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant

class AutonomousWakeupIdleTest {
    @Test fun settingsDraftEditsDoNotChangeLiveConfiguration() {
        val live = AutonomousWakeupSettings()
        val draft = WakeupSettingsDraft.from(live).copy(
            enabled = true, intervalMinutes = 60, startHour = "9", startMinute = "15",
            endHour = "22", endMinute = "45", assistantIds = setOf(kotlin.uuid.Uuid.random()),
        )
        assertFalse(live.enabled)
        assertEquals(30, live.intervalMinutes)
        val saved = draft.applyTo(live)
        assertTrue(saved.enabled)
        assertEquals(60, saved.intervalMinutes)
        assertEquals(9, saved.startHour)
        assertEquals(15, saved.startMinute)
        assertEquals(22, saved.endHour)
        assertEquals(45, saved.endMinute)
        assertEquals(draft.assistantIds, saved.assistantIds)
        assertNotEquals(WakeupSettingsDraft.from(live), draft)
        assertEquals(draft, WakeupSettingsDraft.from(saved))
    }

    @Test fun settingsSavePreservesIndependentlySavedPromptAndRuntimeHistory() {
        val base = AutonomousWakeupSettings(decisionPrompt = "旧提示词")
        val draft = WakeupSettingsDraft.from(base).copy(enabled = true)
        val latest = base.copy(decisionPrompt = "已单独保存的新提示词", lastWakeupAt = mapOf("role" to 123L))
        val saved = draft.applyTo(latest)
        assertEquals(latest.decisionPrompt, saved.decisionPrompt)
        assertEquals(latest.lastWakeupAt, saved.lastWakeupAt)
        assertTrue(saved.enabled)
    }

    @Test fun invalidTimeDraftCannotBeSavedAsPreviousValidValue() {
        val base = AutonomousWakeupSettings()
        listOf(
            WakeupSettingsDraft.from(base).copy(startHour = ""),
            WakeupSettingsDraft.from(base).copy(startHour = "24"),
            WakeupSettingsDraft.from(base).copy(startMinute = "60"),
            WakeupSettingsDraft.from(base).copy(endHour = "-1"),
            WakeupSettingsDraft.from(base).copy(endMinute = "abc"),
        ).forEach { draft ->
            assertTrue(runCatching { draft.applyTo(base) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private val zone = TimeZone.of("Asia/Shanghai")
    private val last = LocalDateTime.parse("2026-11-07T12:00:00")
    private val recent = LocalDateTime.parse("2026-11-07T12:59:00")
    private val now = Instant.parse("2026-11-07T05:00:00Z")
    private fun user(at: LocalDateTime = last) = UIMessage.user("你好").copy(createdAt = at)

    private fun wakeupEvent(type: String = "autonomous_wakeup") = user(recent).copy(
        parts = listOf(UIMessagePart.Text("内部唤醒提示", buildJsonObject { put("beacon_event", type) })),
    )

    @Test fun unansweredWakeupBlocksRepeatedChecksWithoutResettingIdle() {
        val messages = listOf(user(), wakeupEvent(), UIMessage.assistant("在吗？").copy(createdAt = recent))
        repeat(5) { assertTrue(messages.hasUnansweredAutonomousWakeup()) }
        assertEquals(last, messages.lastRealUserMessageAt())
    }

    @Test fun normalAssistantReplyDoesNotBlockFirstWakeup() {
        assertFalse(listOf(user(), UIMessage.assistant("普通回复")).hasUnansweredAutonomousWakeup())
        assertFalse(emptyList<UIMessage>().hasUnansweredAutonomousWakeup())
    }

    @Test fun actualUserReplyReenablesWakeupAndResetsIdle() {
        val messages = listOf(user(), wakeupEvent(), UIMessage.assistant("在吗？"), user(recent), UIMessage.assistant("收到"))
        assertFalse(messages.hasUnansweredAutonomousWakeup())
        assertEquals(recent, messages.lastRealUserMessageAt())
        assertFalse(shouldTriggerAutonomousWakeup(messages.lastRealUserMessageAt(), now, 15, zone))
    }

    @Test fun failedEmptyAndToolOnlyWakeupDoesNotPermanentlyBlockNormalWakeup() {
        assertFalse(listOf(user(), wakeupEvent()).hasUnansweredAutonomousWakeup())
        assertFalse(listOf(user(), wakeupEvent("autonomous_task"), toolMessage(executedTool())).hasUnansweredAutonomousWakeup())
        val empty = UIMessage.assistant("回复为空").copy(parts = listOf(UIMessagePart.Text("回复为空", buildJsonObject { put("beacon_event", "empty_reply") })))
        assertFalse(listOf(user(), wakeupEvent(), empty).hasUnansweredAutonomousWakeup())
        val sent = listOf(user(), wakeupEvent(), UIMessage.assistant("在吗？"))
        assertTrue((sent + user(recent).copy(isSynthetic = true)).hasUnansweredAutonomousWakeup())
        assertTrue((sent + user(recent).copy(isContextCheckpoint = true)).hasUnansweredAutonomousWakeup())
    }

    @Test fun imageUserReplyCountsAndNewWakeupBlocksAgain() {
        val image = user(recent).copy(parts = listOf(UIMessagePart.Image("content://test/image")))
        val messages = listOf(user(), wakeupEvent(), UIMessage.assistant("在吗？"), image)
        assertFalse(messages.hasUnansweredAutonomousWakeup())
        assertEquals(recent, messages.lastRealUserMessageAt())
        assertFalse((messages + wakeupEvent()).hasUnansweredAutonomousWakeup())
        assertTrue((messages + wakeupEvent() + UIMessage.assistant("又来找你了")).hasUnansweredAutonomousWakeup())
    }

    @Test fun ordinaryChatUnansweredWakeupAndUserReplyCycleSurvivesReload() {
        val sent = UIMessage.assistant("在吗？").copy(createdAt = recent)
        val newUser = user(recent)
        val normal = listOf(user(), UIMessage.assistant("普通回复").copy(createdAt = last))
        assertFalse(normal.hasUnansweredAutonomousWakeup())
        assertTrue(shouldTriggerAutonomousWakeup(normal.lastRealUserMessageAt(), Instant.parse("2026-11-07T04:15:00Z"), 15, zone))
        val unanswered = normal + wakeupEvent() + sent
        assertTrue(unanswered.hasUnansweredAutonomousWakeup())
        val replied = unanswered + newUser + UIMessage.assistant("收到").copy(createdAt = recent)
        assertFalse(replied.hasUnansweredAutonomousWakeup())
        assertFalse(shouldTriggerAutonomousWakeup(replied.lastRealUserMessageAt(), Instant.parse("2026-11-07T05:13:59Z"), 15, zone))
        assertTrue(shouldTriggerAutonomousWakeup(replied.lastRealUserMessageAt(), Instant.parse("2026-11-07T05:14:00Z"), 15, zone))
        val serializer = kotlinx.serialization.builtins.ListSerializer(UIMessage.serializer())
        val json = kotlinx.serialization.json.Json
        val restored = json.decodeFromString(serializer, json.encodeToString(serializer, replied))
        assertFalse(restored.hasUnansweredAutonomousWakeup())
        assertEquals(recent, restored.lastRealUserMessageAt())
        assertTrue((restored + wakeupEvent() + sent).hasUnansweredAutonomousWakeup())
    }

    @Test fun idleDeadlineStartsAfterNormalReplyFinishesAndRearmsAfterUserReply() {
        val finish = LocalDateTime.parse("2026-11-07T12:05:00")
        val messages = listOf(user(), UIMessage.assistant("普通回复").copy(createdAt = last, finishedAt = finish))
        assertEquals(finish, messages.lastWakeupIdleActivityAt())
        assertFalse(shouldTriggerAutonomousWakeup(messages.lastWakeupIdleActivityAt(), Instant.parse("2026-11-07T04:19:59Z"), 15, zone))
        assertTrue(shouldTriggerAutonomousWakeup(messages.lastWakeupIdleActivityAt(), Instant.parse("2026-11-07T04:20:00Z"), 15, zone))
        val replied = messages + wakeupEvent() + UIMessage.assistant("在吗？").copy(createdAt = recent) +
            user(recent) + UIMessage.assistant("收到").copy(createdAt = recent)
        assertFalse(replied.hasUnansweredAutonomousWakeup())
        assertEquals(recent, replied.lastWakeupIdleActivityAt())
        assertTrue(shouldTriggerAutonomousWakeup(replied.lastWakeupIdleActivityAt(), Instant.parse("2026-11-07T05:14:00Z"), 15, zone))
        assertNull(listOf(wakeupEvent()).lastWakeupIdleActivityAt())
    }

    @Test fun unrelatedUserMetadataDoesNotSuppressActivityOrRearming() {
        val reply = user(recent).copy(parts = listOf(UIMessagePart.Text("回来了", buildJsonObject { put("beacon_event", "attachment") })))
        val messages = listOf(user(), wakeupEvent(), UIMessage.assistant("在吗"), reply, UIMessage.assistant("欢迎回来"))
        assertEquals(recent, messages.lastRealUserMessageAt())
        assertFalse(messages.hasUnansweredAutonomousWakeup())
    }

    @Test fun defaultTemplateAndLegacyUpgradePreserveCustomPrompts() {
        val legacy = "你刚刚自主醒来。结合最近的聊天和当前时间，决定是否要自然地联系用户；如果没有合适的理由，选择 sleep。不要为了完成定时任务而刻意发消息。"
        assertEquals(DEFAULT_WAKEUP_DECISION_PROMPT, effectiveWakeupPrompt(legacy))
        assertEquals(DEFAULT_WAKEUP_DECISION_PROMPT, effectiveWakeupPrompt("  "))
        assertEquals("我的自定义提示词", effectiveWakeupPrompt("我的自定义提示词"))
        assertTrue(DEFAULT_WAKEUP_DECISION_PROMPT.contains("23:00-7:30"))
        assertTrue(DEFAULT_WAKEUP_DECISION_PROMPT.contains("如果send_message，附上你想说的话。"))
        assertTrue(DEFAULT_WAKEUP_DECISION_PROMPT.contains("do_task + 任务描述"))
        assertEquals(DEFAULT_WAKEUP_DECISION_PROMPT, effectiveWakeupPrompt(PREVIOUS_WAKEUP_DECISION_PROMPT))
    }

    @Test fun promptVariablesRenderOnceWithoutRecursion() {
        assertEquals("角色{{time}}|用户|19:00|60 分钟|{{unknown}}", renderWakeupPrompt(
            "{{char_name}}|{{user_name}}|{{time}}|{{idle_time}}|{{unknown}}",
            "角色{{time}}", "用户", "19:00", "60 分钟",
        ))
    }

    @Test fun decisionJsonKeepsReasonMessageAndActivityLog() {
        val decision = parseWakeupDecision("""{"action":"send_message","reason":"关心进展","message":"今天的事情顺利吗？","activity_log":"在窗边想起了你"}""")!!
        assertEquals("send_message", decision.action)
        assertEquals("关心进展", decision.reason)
        assertEquals("今天的事情顺利吗？", decision.message)
        assertEquals("在窗边想起了你", decision.activityLog)
        assertEquals("旧理由", parseWakeupDecision("""{"action":"send_message","reason":"旧理由"}""")!!.reason)
    }

    @Test fun sleepDecisionKeepsActivityLog() {
        val decision = parseWakeupDecision("""{"action":"sleep","activity_log":"安静地读了一会儿书"}""")!!
        assertEquals("sleep", decision.action)
        assertEquals("安静地读了一会儿书", decision.activityLog)
    }

    @Test fun plainDecisionsFromTemplateRemainCompatible() {
        assertEquals("sleep", parseWakeupDecision("sleep")!!.action)
        assertEquals("睡醒了吗？", parseWakeupDecision("send_message\n睡醒了吗？")!!.message)
        assertEquals("忙完了吗？", parseWakeupDecision("send_message：忙完了吗？")!!.message)
        assertNull(parseWakeupDecision("send_message"))
        assertNull(parseWakeupDecision("maybe sleep later"))
        assertNull(parseWakeupDecision("{broken}"))
        assertNull(parseWakeupDecision("""{"action":"delete"}"""))
    }

    @Test fun taskDecisionsParseTextAndJson() {
        for (text in listOf("do_task + 查询天气", "do_task：查询天气", "do_task\n查询天气", "do_task 查询天气")) {
            assertEquals("查询天气", parseWakeupDecision(text)!!.task)
        }
        assertEquals("查询天气", parseWakeupDecision("""{"action":"do_task","task":"查询天气"}""")!!.task)
        assertEquals("查询天气", parseWakeupDecision("""{"action":"do_task","task_description":"查询天气"}""")!!.task)
        for (text in listOf("do_task", "do_task +", "do_task：", """{"action":"do_task","task":null}""", """{"action":"do_task","task":123}""")) {
            assertNull(text, parseWakeupDecision(text))
        }
    }

    @Test fun taskFallbackKeepsDescriptionWithoutClaimingExecution() {
        val decision = WakeupDecision("do_task", task = "查询天气")
        assertEquals("do_task", decision.resolve(true).action)
        assertTrue(decision.executionPrompt("20:00").contains("MCP工具实际完成"))
        val fallback = decision.resolve(false)
        assertEquals("send_message", fallback.action)
        assertEquals("查询天气", fallback.task)
        assertTrue(fallback.executionPrompt("20:00").contains("不要调用工具或声称任务已完成"))
        assertEquals("sleep", WakeupDecision("sleep").resolve(false).action)
    }

    @Test fun readyToolsRequireSelectionConnectionModelAndEnablement() {
        val server = me.rerere.rikkahub.data.ai.mcp.McpServerConfig.SseTransportServer(
            commonOptions = me.rerere.rikkahub.data.ai.mcp.McpCommonOptions(name = "weather", tools = listOf(
                me.rerere.rikkahub.data.ai.mcp.McpTool(name = "query", description = "查询天气", needsApproval = true),
                me.rerere.rikkahub.data.ai.mcp.McpTool(name = "disabled", enable = false),
            )),
        )
        fun ready(model: Boolean = true, selected: Set<kotlin.uuid.Uuid> = setOf(server.id), connected: Set<kotlin.uuid.Uuid> = setOf(server.id), client: Boolean = true) =
            readyWakeupMcpTools(model, listOf(server), selected, connected) { client }
        assertEquals(1, ready().size)
        assertTrue(ready(model = false).isEmpty())
        assertTrue(ready(selected = emptySet()).isEmpty())
        assertTrue(ready(connected = emptySet()).isEmpty())
        assertTrue(ready(client = false).isEmpty())
        assertTrue(wakeupMcpToolsPrompt(ready()).contains("mcp__weather__query"))
        assertTrue(wakeupMcpToolsPrompt(ready()).contains("需要用户审批"))
        assertTrue(wakeupMcpToolsPrompt(emptyList()).contains("不要选择do_task"))
        assertTrue(readyWakeupMcpTools(true, listOf(server.copy(commonOptions = server.commonOptions.copy(enable = false))), setOf(server.id), setOf(server.id)) { true }.isEmpty())
    }

    private fun executedTool() = UIMessagePart.Tool("call1", "mcp__weather__query", "{}",
        output = listOf(UIMessagePart.Text("晴天")), startedAt = now, finishedAt = now)
    private fun toolMessage(tool: UIMessagePart.Tool) = UIMessage.assistant("").copy(parts = listOf(tool), createdAt = recent)

    @Test fun taskStatusRequiresActualToolExecutionAndCompletedTurn() {
        val messages = listOf(toolMessage(executedTool()))
        assertEquals("completed", wakeupTaskStatus(messages, true, false, last))
        assertEquals("incomplete", wakeupTaskStatus(messages, false, false, last))
        assertEquals("awaiting_approval", wakeupTaskStatus(messages, false, true, last))
        assertEquals("not_executed", wakeupTaskStatus(emptyList(), true, false, last))
        assertEquals("not_executed", wakeupTaskStatus(listOf(toolMessage(executedTool().copy(startedAt = null))), true, false, last))
        assertEquals("🔧 角色 执行了：查询天气", wakeupTaskLabel("角色", "查询天气", "completed"))
    }

    @Test fun failedDeniedAndEmptyRepliesNeverReportSuccess() {
        val error = executedTool().copy(output = listOf(UIMessagePart.Text("失败", buildJsonObject { put("mcp_error", true) })))
        assertEquals("failed", wakeupTaskStatus(listOf(toolMessage(error)), true, false, last))
        val denied = executedTool().copy(approvalState = me.rerere.ai.ui.ToolApprovalState.Denied("拒绝"))
        assertEquals("failed", wakeupTaskStatus(listOf(toolMessage(denied)), true, false, last))
        val empty = UIMessage.assistant("").copy(createdAt = recent, parts = listOf(UIMessagePart.Text("回复为空", buildJsonObject { put("beacon_event", "empty_reply") })))
        assertEquals("incomplete", wakeupTaskStatus(listOf(toolMessage(executedTool()), empty), true, false, last))
    }

    @Test fun taskStateUpdatesOnlyAssociatedEventAndSurvivesSerialization() {
        val task = user().copy(parts = listOf(UIMessagePart.Text("任务提示", buildJsonObject {
            put("beacon_event", "autonomous_task")
            put("task_description", "查询天气")
        })))
        val unrelated = user(recent)
        val conversation = Conversation.ofId(kotlin.uuid.Uuid.random(), assistantId = kotlin.uuid.Uuid.random())
            .copy(messageNodes = listOf(task.toMessageNode(), unrelated.toMessageNode()))
        val updated = conversation.withWakeupTaskStatus(task.id, "completed", "晴天")
        assertEquals(unrelated, updated.currentMessages.last())
        val text = updated.currentMessages.first().parts.single() as UIMessagePart.Text
        assertEquals("completed", (text.metadata!!["task_status"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("晴天", (text.metadata!!["task_result"] as kotlinx.serialization.json.JsonPrimitive).content)
        val json = kotlinx.serialization.json.Json
        val encoded = json.encodeToString(UIMessage.serializer(), updated.currentMessages.first())
        assertEquals(updated.currentMessages.first(), json.decodeFromString(UIMessage.serializer(), encoded))
        assertNull(listOf(task).lastRealUserMessageAt())
    }

    @Test fun thresholdIsInclusive() {
        assertTrue(shouldTriggerAutonomousWakeup(last, now, 60, zone))
        assertFalse(shouldTriggerAutonomousWakeup(last, Instant.parse("2026-11-07T04:59:59Z"), 60, zone))
    }

    @Test fun allConfiguredThresholdsWork() {
        val atThirty = Instant.parse("2026-11-07T04:30:00Z")
        assertTrue(shouldTriggerAutonomousWakeup(last, atThirty, 15, zone))
        assertTrue(shouldTriggerAutonomousWakeup(last, atThirty, 30, zone))
        assertFalse(shouldTriggerAutonomousWakeup(last, atThirty, 60, zone))
    }

    @Test fun absentHistoryDoesNotTrigger() {
        assertNull(emptyList<UIMessage>().lastRealUserMessageAt())
        assertFalse(shouldTriggerAutonomousWakeup(null, now, 15, zone))
    }

    @Test fun futureTimestampDoesNotTrigger() {
        assertFalse(shouldTriggerAutonomousWakeup(last, Instant.parse("2026-11-07T03:00:00Z"), 15, zone))
    }

    @Test fun assistantReplyDoesNotResetIdle() {
        val messages = listOf(user(), UIMessage.assistant("回复").copy(createdAt = recent))
        assertEquals(last, messages.lastRealUserMessageAt())
        assertTrue(shouldTriggerAutonomousWakeup(messages.lastRealUserMessageAt(), now, 60, zone))
    }

    @Test fun wakeupAndOtherInternalEventsDoNotResetIdle() {
        for (event in listOf("autonomous_wakeup", "internal_event")) {
            val internal = user(recent).copy(parts = listOf(UIMessagePart.Text("内部提示", buildJsonObject {
                put("beacon_event", event)
            })))
            assertEquals(last, listOf(user(), internal).lastRealUserMessageAt())
            assertNull(listOf(internal).lastRealUserMessageAt())
        }
    }

    @Test fun syntheticAndCheckpointMessagesAreExcluded() {
        val messages = listOf(user(), user(recent).copy(isSynthetic = true), user(recent).copy(isContextCheckpoint = true))
        assertEquals(last, messages.lastRealUserMessageAt())
    }

    @Test fun continuedChatResetsIdleRegardlessOfListOrder() {
        val messages = listOf(user(recent), user())
        assertEquals(recent, messages.lastRealUserMessageAt())
        assertFalse(shouldTriggerAutonomousWakeup(messages.lastRealUserMessageAt(), now, 60, zone))
    }

    @Test fun imageOnlyUserMessageCountsAsActivity() {
        val image = user(recent).copy(parts = listOf(UIMessagePart.Image("https://example.com/a.png")))
        assertEquals(recent, listOf(user(), image).lastRealUserMessageAt())
        assertFalse(shouldTriggerAutonomousWakeup(listOf(image).lastRealUserMessageAt(), now, 15, zone))
    }

    @Test fun blankUserTextStillCountsAsActivity() {
        assertEquals(recent, listOf(user(), user(recent).copy(parts = listOf(UIMessagePart.Text("\n ")))).lastRealUserMessageAt())
    }

    @Test fun explicitTimeZoneControlsConversion() {
        assertTrue(shouldTriggerAutonomousWakeup(last, now, 60, zone))
        assertFalse(shouldTriggerAutonomousWakeup(last, now, 60, TimeZone.UTC))
    }

    @Test fun invalidThresholdDoesNotTrigger() {
        assertFalse(shouldTriggerAutonomousWakeup(last, now, 0, zone))
        assertFalse(shouldTriggerAutonomousWakeup(last, now, 61, zone))
    }
}
