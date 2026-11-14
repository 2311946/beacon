package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GroupSettingsDraftTest {
    @Test fun `旧配置继承自动总结内置默认值`() {
        val defaults = Json.decodeFromString<GroupChatDefaults>("""{"autoChatRounds":4}""")
        val group = Json.decodeFromString<GroupChat>("""{"name":"旧群","autoModeDelay":8}""")
        assertTrue(defaults.autoSummaryEnabled)
        assertEquals(80, defaults.autoSummaryMessageThreshold)
        assertEquals(20, defaults.summaryKeepRecentMessages)
        assertNull(group.autoSummaryEnabledOverride)
        assertNull(group.autoSummaryMessageThresholdOverride)
        assertNull(group.summaryKeepRecentMessagesOverride)
        assertEquals(defaults.copy(autoModeDelay = 8), group.resolved(defaults))
    }

    @Test fun `自动总结逐字段继承最新全局值且关闭覆盖不被忽略`() {
        val defaults = GroupChatDefaults(autoSummaryEnabled = true, autoSummaryMessageThreshold = 120,
            summaryKeepRecentMessages = 30)
        val inherited = GroupChat()
        assertEquals(defaults, inherited.resolved(defaults))
        val changed = defaults.copy(autoSummaryEnabled = false, autoSummaryMessageThreshold = 200,
            summaryKeepRecentMessages = 45)
        assertEquals(changed, inherited.resolved(changed))
        val group = inherited.copy(autoSummaryEnabledOverride = false, autoSummaryMessageThresholdOverride = 60)
        assertEquals(defaults.copy(autoSummaryEnabled = false, autoSummaryMessageThreshold = 60), group.resolved(defaults))
        assertEquals(changed.copy(autoSummaryMessageThreshold = 60), group.resolved(changed))
        assertFalse(group.resolved(defaults).autoSummaryEnabled)
    }

    @Test fun `自动总结覆盖包含空值和零条数可独立序列化`() {
        val json = Json { encodeDefaults = true }
        val defaults = GroupChatDefaults(autoSummaryEnabled = false, autoSummaryMessageThreshold = 150,
            summaryKeepRecentMessages = 40)
        assertEquals(defaults, Json.decodeFromString<GroupChatDefaults>(Json.encodeToString(defaults)))
        listOf(GroupChat(), GroupChat(autoSummaryEnabledOverride = false,
            autoSummaryMessageThresholdOverride = 40, summaryKeepRecentMessagesOverride = 0)).forEach { group ->
            val encoded = json.encodeToString(group)
            val expected = group.autoSummaryEnabledOverride?.let { JsonPrimitive(it) } ?: JsonNull
            assertEquals(expected, json.parseToJsonElement(encoded).jsonObject["autoSummaryEnabledOverride"])
            assertEquals(group, json.decodeFromString<GroupChat>(encoded))
            assertEquals(group, Json.decodeFromString<GroupChat>(Json.encodeToString(group)))
        }
        assertEquals(0, GroupChat(summaryKeepRecentMessagesOverride = 0).resolved(defaults).summaryKeepRecentMessages)
    }

    @Test fun `自动总结解析规范化全局和群级边界且不修改存储值`() {
        listOf(Int.MIN_VALUE, -1, 0, 39, 40, 50, 80, 300, 301, Int.MAX_VALUE).forEach { threshold ->
            listOf(Int.MIN_VALUE, -1, 0, 20, 39, 40, 50, 51, Int.MAX_VALUE).forEach { keep ->
                val expectedThreshold = threshold.coerceIn(40, 300)
                val expectedKeep = keep.coerceIn(0, minOf(50, expectedThreshold - 1))
                val defaults = GroupChatDefaults(autoSummaryMessageThreshold = threshold, summaryKeepRecentMessages = keep)
                val group = GroupChat(autoSummaryMessageThresholdOverride = threshold, summaryKeepRecentMessagesOverride = keep)
                listOf(GroupChat().resolved(defaults), group.resolved(GroupChatDefaults())).forEach { resolved ->
                    assertEquals(expectedThreshold, resolved.autoSummaryMessageThreshold)
                    assertEquals(expectedKeep, resolved.summaryKeepRecentMessages)
                    assertTrue(resolved.summaryKeepRecentMessages < resolved.autoSummaryMessageThreshold)
                }
                assertEquals(threshold, defaults.autoSummaryMessageThreshold)
                assertEquals(keep, group.summaryKeepRecentMessagesOverride)
            }
        }
    }

    @Test fun `继承保留条数按群级阈值规范化且阈值回升后恢复`() {
        val defaults = GroupChatDefaults(summaryKeepRecentMessages = 50)
        val group = GroupChat(autoSummaryMessageThresholdOverride = 40)
        assertEquals(39, group.resolved(defaults).summaryKeepRecentMessages)
        assertNull(group.summaryKeepRecentMessagesOverride)
        assertEquals(50, group.copy(autoSummaryMessageThresholdOverride = 100).resolved(defaults).summaryKeepRecentMessages)
        assertEquals(39, GroupChat(summaryKeepRecentMessagesOverride = 50)
            .resolved(defaults.copy(autoSummaryMessageThreshold = 40)).summaryKeepRecentMessages)
    }

    @Test fun `总结群级草稿只合并实际修改字段且保留运行中会话`() {
        val base = GroupChat(name = "群")
        val latest = base.copy(conversationId = Uuid.random(), chatModelId = Uuid.random(),
            autoSummaryEnabledOverride = true, autoSummaryMessageThresholdOverride = 150,
            summaryKeepRecentMessagesOverride = 40, systemPromptOverride = "外部提示词")
        val drafts = listOf(
            base.copy(autoSummaryEnabledOverride = false) to latest.copy(autoSummaryEnabledOverride = false),
            base.copy(autoSummaryMessageThresholdOverride = 60) to latest.copy(autoSummaryMessageThresholdOverride = 60),
            base.copy(summaryKeepRecentMessagesOverride = 0) to latest.copy(summaryKeepRecentMessagesOverride = 0),
        )
        drafts.forEach { (draft, expected) ->
            assertEquals(expected, mergeGroupSettingsDraft(latest, base, draft.copy(conversationId = Uuid.random())))
        }
        val reverted = base.copy(autoSummaryEnabledOverride = false).copy(autoSummaryEnabledOverride = null)
        assertEquals(latest, mergeGroupSettingsDraft(latest, base, reverted))
        assertNull(base.autoSummaryEnabledOverride)
    }

    @Test fun `总结全局草稿只合并实际修改字段`() {
        val base = GroupChatDefaults()
        val latest = base.copy(autoSummaryEnabled = false, autoSummaryMessageThreshold = 200,
            summaryKeepRecentMessages = 45, autoChatRounds = 7)
        assertEquals(latest.copy(autoSummaryEnabled = true), mergeGroupDefaultsDraft(latest,
            base.copy(autoSummaryEnabled = false), base))
        assertEquals(latest.copy(autoSummaryMessageThreshold = 60),
            mergeGroupDefaultsDraft(latest, base, base.copy(autoSummaryMessageThreshold = 60)))
        assertEquals(latest.copy(summaryKeepRecentMessages = 0),
            mergeGroupDefaultsDraft(latest, base, base.copy(summaryKeepRecentMessages = 0)))
        assertEquals(latest, mergeGroupDefaultsDraft(latest, base, base))
        assertEquals(80, base.autoSummaryMessageThreshold)
    }

    @Test fun `总结恢复继承保存后跟随全局且保留其他字段`() {
        val memberId = Uuid.random()
        val base = GroupChat(name = "群", memberIds = listOf(memberId), disabledMemberIds = listOf(memberId),
            autoSummaryEnabledOverride = false, autoSummaryMessageThresholdOverride = 60,
            summaryKeepRecentMessagesOverride = 0)
        val draft = base.copy(autoSummaryEnabledOverride = null, autoSummaryMessageThresholdOverride = null,
            summaryKeepRecentMessagesOverride = null)
        val latest = base.copy(conversationId = Uuid.random())
        val saved = mergeGroupSettingsDraft(latest, base, draft)
        assertEquals(draft.copy(conversationId = latest.conversationId), saved)
        assertFalse(base.resolved(GroupChatDefaults()).autoSummaryEnabled)
        assertEquals(0, base.summaryKeepRecentMessagesOverride)
        val restored = Json.decodeFromString<GroupChat>(Json.encodeToString(saved))
        val defaults = GroupChatDefaults(autoSummaryMessageThreshold = 180, summaryKeepRecentMessages = 45)
        assertEquals(defaults, restored.resolved(defaults))
    }

    @Test fun `old JSON inherits the default system prompt`() {
        val defaults = Json.decodeFromString<GroupChatDefaults>("""{"autoChatRounds":4}""")
        val group = Json.decodeFromString<GroupChat>("""{"name":"旧群","autoModeDelay":8}""")
        assertEquals(DEFAULT_GROUP_CHAT_SYSTEM_PROMPT, defaults.systemPrompt)
        assertNull(group.systemPromptOverride)
        assertEquals(DEFAULT_GROUP_CHAT_SYSTEM_PROMPT, group.resolved(defaults).systemPrompt)
        assertEquals("更新的全局提示词", group.resolved(defaults.copy(systemPrompt = "更新的全局提示词")).systemPrompt)
    }

    @Test fun `null and empty system prompt overrides serialize distinctly`() {
        val json = Json { encodeDefaults = true }
        val defaults = GroupChatDefaults(systemPrompt = "全局提示词")
        listOf<String?>(null, "", " \n ").forEach { prompt ->
            val group = GroupChat(name = "群", systemPromptOverride = prompt)
            val encoded = json.encodeToString(group)
            val expected = if (prompt == null) JsonNull else JsonPrimitive(prompt)
            assertEquals(expected, json.parseToJsonElement(encoded).jsonObject["systemPromptOverride"])
            val restored = json.decodeFromString<GroupChat>(encoded)
            assertEquals(prompt, restored.systemPromptOverride)
            assertEquals(prompt ?: defaults.systemPrompt, restored.resolved(defaults).systemPrompt)
            assertEquals(group, Json.decodeFromString<GroupChat>(Json.encodeToString(group)))
        }
        val emptyDefaults = GroupChatDefaults(systemPrompt = "")
        assertEquals(emptyDefaults, Json.decodeFromString<GroupChatDefaults>(Json.encodeToString(emptyDefaults)))
        assertEquals("", GroupChat().resolved(emptyDefaults).systemPrompt)
    }

    @Test fun `multiline system prompt saves without trimming or overwriting live fields`() {
        val base = GroupChat(name = "群")
        val prompt = "  第一行对白\n*第二行动作*\n（第三行心理）\n"
        val draft = base.copy(systemPromptOverride = prompt)
        val latest = base.copy(conversationId = Uuid.random(), chatModelId = Uuid.random(), autoModeDelayOverride = 17)
        assertNull(base.systemPromptOverride)
        val saved = mergeGroupSettingsDraft(latest, base, draft)
        assertEquals(latest.copy(systemPromptOverride = prompt), saved)
        assertEquals(prompt, saved.resolved(GroupChatDefaults()).systemPrompt)
        assertEquals(saved, Json.decodeFromString<GroupChat>(Json.encodeToString(saved)))
        assertEquals("", mergeGroupSettingsDraft(saved, saved, saved.copy(systemPromptOverride = "")).systemPromptOverride)
        assertEquals(latest.copy(systemPromptOverride = "外部提示词"), mergeGroupSettingsDraft(
            latest.copy(systemPromptOverride = "外部提示词"), base, base,
        ))
    }

    @Test fun `global prompt merge preserves external fields and untouched prompt edits`() {
        val base = GroupChatDefaults()
        val prompt = "第一行\n第二行\n"
        val latest = base.copy(activationStrategy = GroupActivationStrategy.MANUAL, autoChatRounds = 7,
            autoModeDelay = 21, singleChatContextCount = 9)
        val saved = mergeGroupDefaultsDraft(latest, base, base.copy(systemPrompt = prompt))
        assertEquals(DEFAULT_GROUP_CHAT_SYSTEM_PROMPT, base.systemPrompt)
        assertEquals(latest.copy(systemPrompt = prompt), saved)
        assertEquals(saved, Json.decodeFromString<GroupChatDefaults>(Json.encodeToString(saved)))
        assertEquals(latest.copy(systemPrompt = ""), mergeGroupDefaultsDraft(latest, base, base.copy(systemPrompt = "")))
        val externalPrompt = base.copy(systemPrompt = "外部全局提示词")
        assertEquals(externalPrompt.copy(autoChatRounds = 6), mergeGroupDefaultsDraft(
            externalPrompt, base, base.copy(autoChatRounds = 6),
        ))
        assertEquals(externalPrompt, mergeGroupDefaultsDraft(externalPrompt, base, base))
    }

    @Test fun `restoring prompt inheritance follows current global defaults only after save`() {
        val defaults = GroupChatDefaults(systemPrompt = "当前全局提示词")
        listOf("自定义提示词", "", DEFAULT_GROUP_CHAT_SYSTEM_PROMPT).forEach { prompt ->
            val base = GroupChat(name = "群", systemPromptOverride = prompt)
            val draft = base.copy(systemPromptOverride = null)
            assertEquals(prompt, base.resolved(defaults).systemPrompt)
            val saved = mergeGroupSettingsDraft(base, base, draft)
            assertNull(saved.systemPromptOverride)
            assertEquals(defaults.systemPrompt, saved.resolved(defaults).systemPrompt)
            assertEquals("新的全局提示词", saved.resolved(defaults.copy(systemPrompt = "新的全局提示词")).systemPrompt)
        }
    }

    @Test fun `group avatar is saved only when its draft changes`() {
        val base = GroupChat(name = "群")
        val avatar = Avatar.Image("file:///group-avatar.png")
        val draft = base.copy(avatar = avatar)
        val latest = base.copy(conversationId = Uuid.random())
        val saved = mergeGroupSettingsDraft(latest, base, draft)
        assertEquals(Avatar.Dummy, base.avatar)
        assertEquals(avatar, saved.avatar)
        assertEquals(latest.conversationId, saved.conversationId)
        assertEquals(latest.copy(avatar = avatar), saved)
        assertEquals(saved, mergeGroupSettingsDraft(saved, base, base))
        assertEquals(Avatar.Dummy, mergeGroupSettingsDraft(saved, saved, saved.copy(avatar = Avatar.Dummy)).avatar)
    }

    @Test fun `group avatar persists and old groups default to no avatar`() {
        assertEquals(Avatar.Dummy, Json.decodeFromString<GroupChat>("{}").avatar)
        listOf(Avatar.Dummy, Avatar.Emoji("群"), Avatar.Image("file:///group-avatar.png")).forEach { avatar ->
            val group = GroupChat(name = "群", avatar = avatar)
            assertEquals(group, Json.decodeFromString<GroupChat>(Json.encodeToString(group)))
        }
    }

    @Test fun `editing a draft does not change saved group`() {
        val base = GroupChat(name = "旧名称")
        val draft = base.copy(name = "新名称", autoChatRoundsOverride = 4)
        assertEquals("旧名称", base.name)
        assertNull(base.autoChatRoundsOverride)
        val saved = mergeGroupSettingsDraft(base, base, draft)
        assertEquals("新名称", saved.name)
        assertEquals(4, saved.autoChatRoundsOverride)
    }

    @Test fun `save preserves live conversation model and unrelated settings`() {
        val base = GroupChat(name = "群")
        val latest = base.copy(conversationId = Uuid.random(), chatModelId = Uuid.random(), enabled = false,
            autoModeDelayOverride = 19)
        val saved = mergeGroupSettingsDraft(latest, base, base.copy(name = " 新群 "))
        assertEquals(latest.copy(name = "新群"), saved)
    }

    @Test fun `reset inheritance remains draft until committed`() {
        val base = GroupChat(name = "群", autoChatRoundsOverride = 8, singleChatContextCountOverride = 10,
            systemPromptOverride = "自定义提示词")
        val draft = base.copy(autoChatRoundsOverride = null, singleChatContextCountOverride = null, systemPromptOverride = null)
        assertEquals(8, base.autoChatRoundsOverride)
        assertEquals("自定义提示词", base.systemPromptOverride)
        val saved = mergeGroupSettingsDraft(base, base, draft)
        assertNull(saved.autoChatRoundsOverride)
        assertNull(saved.singleChatContextCountOverride)
        assertNull(saved.systemPromptOverride)
        assertEquals(GroupChatDefaults(), saved.resolved(GroupChatDefaults()))
    }

    @Test fun `members and muted members commit together`() {
        val first = Uuid.random()
        val second = Uuid.random()
        val base = GroupChat(name = "群", memberIds = listOf(first), disabledMemberIds = listOf(first))
        val draft = base.copy(memberIds = listOf(second), disabledMemberIds = emptyList())
        assertEquals(draft, mergeGroupSettingsDraft(base, base, draft))
        assertEquals(listOf(first), base.memberIds)
    }

    @Test fun `global draft edits are isolated and preserve unrelated current fields`() {
        val base = GroupChatDefaults()
        val draft = base.copy(autoChatRounds = 6)
        val latest = base.copy(singleChatContextCount = 15)
        assertEquals(2, base.autoChatRounds)
        assertEquals(latest.copy(autoChatRounds = 6), mergeGroupDefaultsDraft(latest, base, draft))
    }

    @Test fun `unchanged and reverted drafts do not overwrite external edits`() {
        val base = GroupChat(name = "群")
        val latest = base.copy(name = "外部更新", autoChatRoundsOverride = 9)
        assertEquals(latest, mergeGroupSettingsDraft(latest, base, base))
        val defaults = GroupChatDefaults()
        val latestDefaults = defaults.copy(autoModeDelay = 22)
        assertEquals(latestDefaults, mergeGroupDefaultsDraft(latestDefaults, defaults, defaults))
    }
}
