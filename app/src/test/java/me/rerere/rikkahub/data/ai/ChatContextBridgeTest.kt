package me.rerere.rikkahub.data.ai

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.model.GroupChatDefaults
import me.rerere.rikkahub.data.model.normalizeChatContextCount
import org.junit.Assert.*
import org.junit.Test

class ChatContextBridgeTest {
    private fun message(day: Int, text: String = "消息$day") = UIMessage.user(text)
        .copy(createdAt = LocalDateTime(2026, 11, day, 9, 30))

    @Test fun recentMessagesAreSortedAndLimitedAcrossConversations() {
        val messages = (1..8).map { message(it) }
        assertEquals(messages.takeLast(5), recentBridgeMessages(messages.reversed(), 5))
        assertEquals(messages.takeLast(1), recentBridgeMessages(messages, 1))
    }

    @Test fun internalMessagesAreFilteredBeforeCounting() {
        val real = message(1)
        val synthetic = message(2).copy(isSynthetic = true)
        val checkpoint = message(3).copy(isContextCheckpoint = true)
        val event = message(4).copy(parts = listOf(UIMessagePart.Text("唤醒内部提示", buildJsonObject {
            put("beacon_event", "autonomous_wakeup")
        })))
        assertEquals(listOf(real), recentBridgeMessages(listOf(real, synthetic, checkpoint, event, UIMessage.system("秘密")), 5))
    }

    @Test fun emptyTextAndDuplicateMessagesDoNotUseQuota() {
        val real = message(1)
        assertEquals(listOf(real), recentBridgeMessages(listOf(real, real, message(2, "  ")), 5))
    }

    @Test fun assistantTextIsIncludedWithoutReasoning() {
        val assistant = UIMessage.assistant("回复")
        assertEquals("回复", bridgeMessageText(assistant))
    }

    @Test fun countMigrationDefaultsAndOverridesAreBounded() {
        assertEquals(5, GroupChatDefaults().singleChatContextCount)
        assertEquals(5, normalizeChatContextCount(0))
        assertEquals(5, normalizeChatContextCount(-1))
        assertEquals(10, normalizeChatContextCount(20))
        assertEquals(1, normalizeChatContextCount(1))
        assertEquals(8, GroupChat(singleChatContextCountOverride = 8).resolved(GroupChatDefaults()).singleChatContextCount)
        assertEquals(7, GroupChat().resolved(GroupChatDefaults(singleChatContextCount = 7)).singleChatContextCount)
    }

    @Test fun turnContextIsUserAndLeavesHistoricalPrefixUntouched() {
        val history = listOf(UIMessage.system("固定系统"), message(1), UIMessage.assistant("回复"))
        val context = createTurnUserContext("[当前时间：2026年11月8日 周日 09:30]", "[群聊中的最近对话]\n[甲]: 你好")
        assertEquals(me.rerere.ai.core.MessageRole.USER, context.role)
        assertTrue(context.isSynthetic)
        assertTrue(context.toText().startsWith("[当前时间："))
        assertTrue(context.toText().contains("[群聊中的最近对话]"))
        val first = history + context
        val next = history + createTurnUserContext("[当前时间：2026年11月8日 周日 10:30]", "新上下文")
        assertEquals(first.take(history.size), next.take(history.size))
        assertNotEquals(first.last().toText(), next.last().toText())
    }

    @Test fun forkMessagesKeepTheirOwnConversationSourceAndExcludeSummaries() {
        val shared = message(1)
        val source = me.rerere.rikkahub.data.model.Conversation.ofId(kotlin.uuid.Uuid.random())
            .copy(title = "私聊一").updateCurrentMessages(listOf(shared))
        val fork = source.copy(id = kotlin.uuid.Uuid.random(), title = "复制私聊")
            .updateCurrentMessages(listOf(shared, message(2).copy(isContextCheckpoint = true)))
        val recent = recentSourcedBridgeMessages(listOf(source, fork), 5)
        assertEquals(2, recent.size)
        assertEquals(setOf(source.id, fork.id), recent.map { it.conversationId }.toSet())
        assertTrue(recent.all { it.message.id == shared.id })
        recent.forEach { assertEquals(if (it.conversationId == source.id) "私聊一" else "复制私聊", it.conversationTitle) }
    }

    @Test fun currentTimeUsesExactChineseFormat() {
        assertEquals("[当前时间：2026年11月8日 周日 09:30]", formatCurrentChatTime(java.time.LocalDateTime.of(2026, 11, 8, 9, 30)))
        assertEquals("[当前时间：2026年11月9日 周一 00:05]", formatCurrentChatTime(java.time.LocalDateTime.of(2026, 11, 9, 0, 5)))
    }
}
