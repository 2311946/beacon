package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GroupChatRecordTest {
    @Test
    fun checkpointIsSeparateFromPreviousAndFollowingMessages() {
        val messages = listOf(
            UIMessage.user("原问题"),
            UIMessage.assistant("原回复"),
            UIMessage.user("压缩摘要").copy(isContextCheckpoint = true),
            UIMessage.assistant("压缩后的回复"),
            UIMessage.user("下一轮"),
        )
        val nodes = messages.map { it.toMessageNode() }
        val rounds = nodes.toChatPreviewRounds(emptyMap())
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3), listOf(4)),
            rounds.map { round -> round.entries.map { it.index } })
        assertEquals(nodes[2].id, rounds[1].id)
        assertEquals(listOf(false, true, false, false), rounds.map { it.checkpoint })
        assertEquals(nodes[3].id, rounds[2].id)
        assertEquals("[上下文检查点]", rounds[1].entries.single().text)
        assertFalse(rounds[1].matches("压缩摘要"))
        assertEquals(messages, nodes.map { it.currentMessage })
    }

    @Test
    fun consecutiveAndNonUserCheckpointsRemainSeparate() {
        val messages = listOf(
            UIMessage.assistant("普通回复"),
            UIMessage.assistant("摘要一").copy(isContextCheckpoint = true),
            UIMessage.user("摘要二").copy(isContextCheckpoint = true),
            UIMessage.assistant("之后"),
        )
        val rounds = messages.map { it.toMessageNode() }.toChatPreviewRounds(emptyMap())
        assertEquals(listOf(0, 1, 2, 3), rounds.map { it.firstIndex })
        assertTrue(rounds.all { it.entries.size == 1 })
        assertEquals(listOf(false, true, true, false), rounds.map { it.checkpoint })
    }

    @Test
    fun membersSharingModelKeepNamesAndOriginalJumpIndices() {
        val first = Assistant(name = "角色甲")
        val second = Assistant(name = "角色乙")
        val modelId = Uuid.random()
        val messages = listOf(UIMessage.user("大家好"),
            UIMessage.assistant("甲的回复").copy(modelId = modelId),
            UIMessage.assistant("乙的回复").copy(modelId = modelId), UIMessage.user("下一轮"))
        val conversation = Conversation(assistantId = first.id,
            messageNodes = messages.map { it.toMessageNode() },
            config = ConversationConfig(isGroupChat = true, speakerMap = mapOf(messages[1].id to first.id, messages[2].id to second.id)))
        val names = conversation.chatRecordSenderNames(Settings(assistants = listOf(first, second)), true)
        val rounds = conversation.messageNodes.toChatPreviewRounds(names)
        assertEquals(2, rounds.size)
        assertEquals(listOf(0, 1, 2), rounds.first().entries.map { it.index })
        assertEquals("角色甲", rounds.first().entries[1].senderName)
        assertEquals("角色乙", rounds.first().entries[2].senderName)
        assertEquals(3, rounds.last().firstIndex)
        assertTrue(rounds.first().matches("乙的回复"))
        assertTrue(rounds.first().matches("角色甲"))
        assertEquals(messages, conversation.currentMessages)
    }

    @Test
    fun previewJumpResolvesNodeAfterEarlierMessageDeleted() {
        val nodes = listOf(UIMessage.user("开头"), UIMessage.assistant("目标"), UIMessage.user("结尾"))
            .map { it.toMessageNode() }
        val target = nodes.toChatPreviewRounds(emptyMap()).first().entries[1]
        assertEquals(nodes[1].id, target.nodeId)
        assertEquals(1, target.index)
        assertEquals(0, nodes.drop(1).chatPreviewIndexOf(target.nodeId))
        assertEquals(-1, nodes.filterNot { it.id == target.nodeId }.chatPreviewIndexOf(target.nodeId))
    }

    @Test
    fun previewJumpStillFindsNodeAfterSelectedVersionChanges() {
        val original = UIMessage.assistant("旧版本")
        val node = original.toMessageNode()
        val entry = listOf(node).toChatPreviewRounds(emptyMap()).single().entries.single()
        val changed = node.copy(messages = listOf(UIMessage.assistant("新版本")), selectIndex = 0)
        assertEquals(0, listOf(changed).chatPreviewIndexOf(entry.nodeId))
        assertNotEquals(entry.message.id, changed.currentMessage.id)
    }

    @Test
    fun internalWakeupDoesNotExposePromptOrDecision() {
        val message = UIMessage.user("内部提示绝不展示").copy(parts = listOf(UIMessagePart.Text("内部提示绝不展示", metadata = buildJsonObject {
            put("beacon_event", JsonPrimitive("autonomous_wakeup"))
            put("decision_content", JsonPrimitive("内部决策绝不展示"))
        })))
        val projected = message.forChatRecord()
        assertEquals(MessageRole.SYSTEM, projected.role)
        assertEquals("[主动联系事件]", projected.toText())
        assertNull(projected.parts.single().metadata)
        assertTrue(message.toText().contains("内部提示"))
        assertNotNull(message.parts.single().metadata)
    }

    @Test
    fun longTextSearchUsesFullTextButSnippetIsBounded() {
        val text = "前文".repeat(1000) + "检索目标" + "后文".repeat(1000)
        val message = UIMessage.assistant(text)
        val round = listOf(message.toMessageNode()).toChatPreviewRounds(emptyMap()).single()
        assertTrue(round.matches("检索目标"))
        val snippet = chatPreviewSnippet(text, "检索目标")
        assertTrue(snippet.length <= 300)
        assertTrue(snippet.contains("检索目标"))
        assertEquals(text, message.forChatRecord().toText())
    }

    @Test
    fun missingMemberNeverBorrowsAnotherAssistantName() {
        val assistant = Assistant(name = "不可借用的角色")
        val message = UIMessage.assistant("保留原文")
        val conversation = Conversation(assistantId = assistant.id,
            messageNodes = listOf(message.toMessageNode()), config = ConversationConfig(isGroupChat = true))
        val names = conversation.chatRecordSenderNames(Settings(assistants = listOf(assistant)), true)
        assertEquals("未知成员", names[message.id])
        assertEquals("保留原文", message.forChatRecord().toText())
    }
}
