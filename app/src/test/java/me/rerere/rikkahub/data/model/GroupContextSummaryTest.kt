package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupContextSummaryTest {
    private fun conversation(messages: List<UIMessage> = List(5) { UIMessage.user("原文 $it") }) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() },
        config = ConversationConfig(isGroupChat = true),
    )

    private fun Conversation.stage(
        source: List<UIMessage>,
        topics: List<GroupSummaryTopic> = listOf(GroupSummaryTopic("主题", "本阶段摘要")),
    ) = GroupSummaryStage(
        conversationId = id,
        sourceMessageIds = source.map { it.id },
        sourceFingerprint = groupSummarySourceFingerprint(source),
        topics = topics,
    )

    private fun Conversation.withStages(vararg stages: GroupSummaryStage) =
        copy(config = config!!.copy(groupSummaryStages = stages.toList()))

    @Test
    fun `all topics and independent stages enter one checkpoint while original messages remain untouched`() {
        val original = conversation()
        val messages = original.currentMessages
        val first = original.stage(
            messages.take(2),
            listOf(GroupSummaryTopic("计划", "角色甲提出计划"), GroupSummaryTopic("分歧", "角色乙尚未同意")),
        )
        val second = original.stage(messages.subList(2, 4), listOf(GroupSummaryTopic("待办", "用户负责确认")))
        val source = original.withStages(first, second)

        val history = source.groupSummaryHistory()

        assertEquals(listOf(first, second), source.validGroupSummaryStages())
        assertEquals(2, history.size)
        assertEquals(messages.last(), history.last())
        assertTrue(history.first().isSynthetic)
        assertTrue(history.first().isContextCheckpoint)
        listOf("计划", "角色甲提出计划", "分歧", "角色乙尚未同意", "待办", "用户负责确认").forEach {
            assertTrue(history.first().toText().contains(it))
        }
        assertEquals(original.messageNodes, source.messageNodes)
        assertEquals(messages, source.currentMessages)
        assertFalse(source.currentMessages.any { it.isSynthetic || it.isContextCheckpoint })
        assertEquals(second.sourceFingerprint, original.groupSummarySourceFingerprint(messages.subList(2, 4)))
    }

    @Test
    fun `stage identity is scoped to its conversation and single chats never compress`() {
        val source = conversation()
        val summarized = source.withStages(source.stage(source.currentMessages.take(2)))
        val other = summarized.copy(id = Uuid.random())
        val single = summarized.copy(config = summarized.config!!.copy(isGroupChat = false))
        val unbound = summarized.copy(config = null)

        listOf(other, single, unbound).forEach {
            assertTrue(it.validGroupSummaryStages().isEmpty())
            assertEquals(source.currentMessages, it.groupSummaryHistory())
        }
        assertNotEquals(
            source.groupSummarySourceFingerprint(source.currentMessages),
            other.groupSummarySourceFingerprint(source.currentMessages),
        )
    }

    @Test
    fun `edit branch deletion insertion and reorder invalidate the first stage and all later stages`() {
        val original = conversation()
        val messages = original.currentMessages
        val source = original.withStages(original.stage(messages.take(2)), original.stage(messages.subList(2, 4)))
        val variants = listOf(
            listOf(messages[0].copy(parts = listOf(UIMessagePart.Text("修改正文")))) + messages.drop(1),
            listOf(messages[0].copy(id = Uuid.random())) + messages.drop(1),
            messages.drop(1),
            listOf(UIMessage.user("插入历史")) + messages,
            listOf(messages[1], messages[0]) + messages.drop(2),
        )
        variants.forEach { history ->
            assertTrue(source.validGroupSummaryStages(history).isEmpty())
            assertSame(history, source.groupSummaryHistory(history))
        }
    }

    @Test
    fun `later invalid stage retains its raw source while earlier valid stage still compresses`() {
        val original = conversation()
        val messages = original.currentMessages
        val first = original.stage(messages.take(2))
        val second = original.stage(messages.subList(2, 4))
        val source = original.withStages(first, second)
        val edited = messages.take(2) + messages[2].copy(parts = listOf(UIMessagePart.Text("修改后续阶段"))) + messages.drop(3)

        assertEquals(listOf(first), source.validGroupSummaryStages(edited))
        assertEquals(edited.drop(2), source.groupSummaryHistory(edited).drop(1))
    }

    @Test
    fun `stages must begin at original history start with no gaps or overlaps`() {
        val source = conversation()
        val messages = source.currentMessages
        assertTrue(source.withStages(source.stage(messages.subList(1, 3))).validGroupSummaryStages().isEmpty())
        val first = source.stage(messages.take(2))
        listOf(messages.subList(1, 3), messages.subList(3, 5)).forEach { next ->
            assertEquals(listOf(first), source.withStages(first, source.stage(next)).validGroupSummaryStages())
        }
        assertTrue(source.withStages(source.stage(emptyList())).validGroupSummaryStages().isEmpty())
    }

    @Test
    fun `speaker mapping is fingerprinted only for stage source messages`() {
        val roleId = Uuid.random()
        val original = conversation(listOf(UIMessage.assistant("提议"), UIMessage.assistant("待办")))
        val mapped = original.copy(config = original.config!!.copy(speakerMap = mapOf(original.currentMessages[0].id to roleId)))
        val first = mapped.stage(mapped.currentMessages.take(1))
        val source = mapped.withStages(first)
        val changed = source.copy(config = source.config!!.copy(speakerMap = mapOf(original.currentMessages[0].id to Uuid.random())))
        val sourceConfig = source.config!!
        val appendedMapping = source.copy(config = sourceConfig.copy(speakerMap = sourceConfig.speakerMap + (original.currentMessages[1].id to roleId)))

        assertTrue(changed.validGroupSummaryStages().isEmpty())
        assertEquals(listOf(first), appendedMapping.validGroupSummaryStages())
    }

    @Test
    fun `checkpoint id and timestamp are stable across calls appends and serialization`() {
        val original = conversation()
        val source = original.withStages(original.stage(original.currentMessages.take(2)))
        val first = source.groupSummaryHistory().first()
        assertEquals(first, source.groupSummaryHistory().first())
        val appended = source.currentMessages + UIMessage.user("新增消息")
        assertEquals(first, source.groupSummaryHistory(appended).first())
        val restored = Json.decodeFromString<Conversation>(Json.encodeToString(source))
        assertEquals(first, restored.groupSummaryHistory().first())
        val changed = source.withStages(source.config!!.groupSummaryStages.single().copy(topics = listOf(GroupSummaryTopic("新主题", "新摘要"))))
        assertNotEquals(first.id, changed.groupSummaryHistory().first().id)
    }

    @Test
    fun `attachments tool inputs and complete outputs are fingerprinted and included in neutral transcript`() {
        val speakerId = Uuid.random()
        val tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "read_file",
            input = "{\"path\":\"notes.txt\"}",
            output = listOf(UIMessagePart.Text("完整工具结果"), UIMessagePart.Image("file:///nested.png")),
        )
        val assistant = UIMessage.assistant("说明").copy(parts = listOf(UIMessagePart.Text("正文"), tool, UIMessagePart.Document("file:///notes.txt", "notes.txt")))
        val original = conversation(listOf(UIMessage.user("用户发言"), assistant))
        val source = original.copy(config = original.config!!.copy(speakerMap = mapOf(assistant.id to speakerId)))
        val transcript = buildGroupSummaryTranscript(source, source.currentMessages, mapOf(speakerId to "角色甲"), "用户昵称")
        val rows = Json.parseToJsonElement(transcript).jsonObject.getValue("messages").jsonArray
        assertEquals("用户昵称", rows[0].jsonObject.getValue("speakerName").jsonPrimitive.content)
        assertEquals("角色甲", rows[1].jsonObject.getValue("speakerName").jsonPrimitive.content)
        assertEquals(speakerId.toString(), rows[1].jsonObject.getValue("speakerId").jsonPrimitive.content)
        val serializedMessage = Json.decodeFromJsonElement(UIMessage.serializer(), rows[1].jsonObject.getValue("message"))
        assertEquals(assistant, serializedMessage)
        assertTrue(transcript.contains(assistant.createdAt.toString()))
        assertTrue(transcript.contains("完整工具结果"))
        assertTrue(transcript.contains("file:///nested.png"))
        assertTrue(transcript.contains("notes.txt"))

        val summarized = source.withStages(source.stage(source.currentMessages))
        listOf(
            assistant.copy(parts = listOf(tool.copy(input = "修改参数"))),
            assistant.copy(parts = listOf(tool.copy(output = listOf(UIMessagePart.Text("修改结果"))))),
            assistant.copy(parts = assistant.parts + UIMessagePart.Image("file:///new.png")),
        ).forEach { edited ->
            assertTrue(summarized.validGroupSummaryStages(listOf(source.currentMessages.first(), edited)).isEmpty())
        }
    }
}
