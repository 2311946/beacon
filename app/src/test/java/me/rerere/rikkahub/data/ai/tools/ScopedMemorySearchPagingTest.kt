package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ScopedMemorySearchPagingTest {
    private val assistantId = Uuid.random()
    private val otherAssistantId = Uuid.random()
    private val offsets = mutableListOf<Int>()
    private val scopes = mutableListOf<Set<Uuid>>()
    private val reads = mutableListOf<Pair<Conversation, UIMessage>>()

    private fun conversation(rejectedCount: Int, visibleCount: Int): Conversation {
        val rejected = List(rejectedCount) { index ->
            when (index % 5) {
                0 -> MessageNode(
                    messages = listOf(UIMessage.assistant("旧分支秘密"), UIMessage.assistant("当前分支")),
                    selectIndex = 1,
                )
                1 -> MessageNode.of(UIMessage.user("合成秘密").copy(isSynthetic = true))
                2 -> MessageNode.of(UIMessage.user("检查点秘密").copy(isContextCheckpoint = true))
                3 -> MessageNode.of(UIMessage.user("系统秘密").copy(role = MessageRole.SYSTEM))
                else -> MessageNode.of(UIMessage.assistant("   "))
            }
        }
        val visible = List(visibleCount) { index -> MessageNode.of(UIMessage.user("可见正文$index".repeat(3_000))) }
        return Conversation(assistantId = assistantId, title = "当前标题", messageNodes = rejected + visible)
    }

    private fun Conversation.hits(): List<MessageSearchResult> = messageNodes.map { node ->
        MessageSearchResult(
            nodeId = node.id.toString(),
            messageId = node.messages.first().id.toString(),
            conversationId = id.toString(),
            title = "过时标题",
            updateAt = Instant.EPOCH,
            snippet = "禁止泄露的索引秘密",
        )
    }

    private fun tools(conversation: Conversation, hits: List<MessageSearchResult> = conversation.hits()): List<Tool> =
        createScopedMemoryConversationTools(
            assistantId = assistantId,
            allowedGroups = emptyList(),
            allGroupConversationIds = emptySet(),
            getConversationsOfAssistant = { listOf(conversation.copy(messageNodes = emptyList())) },
            searchMessages = { _, _ -> error("分页回调存在时不应调用旧入口") },
            getConversationById = { id -> conversation.takeIf { it.id == id } },
            onRead = { source, messages -> messages.forEach { reads += source to it } },
            searchMessagesPage = { query, ids, offset ->
                assertEquals("关键词", query)
                scopes += ids
                offsets += offset
                hits.drop(offset).take(50)
            },
        )

    private suspend fun List<Tool>.search(args: JsonObjectBuilder.() -> Unit = {}): JsonArray {
        val response = single { it.name == "conversation_search" }.execute(buildJsonObject {
            put("query", "关键词")
            args()
        }).single() as UIMessagePart.Text
        return Json.parseToJsonElement(response.text).jsonArray
    }

    @Test
    fun `搜索跳过两整页无效命中后补足且保留原始来源`() = runBlocking {
        val conversation = conversation(rejectedCount = 100, visibleCount = 3)
        val results = tools(conversation).search { put("limit", 2) }

        assertEquals(listOf(0, 50, 100), offsets)
        assertTrue(scopes.all { it == setOf(conversation.id) })
        assertEquals(2, results.size)
        val originals = conversation.messageNodes.drop(100).take(2).map { it.messages.single() }
        assertEquals(originals.map { it.id.toString() }, results.map {
            it.jsonObject.getValue("messageId").jsonPrimitive.content
        })
        results.forEachIndexed { index, element ->
            val result = element.jsonObject
            assertEquals("当前标题", result.getValue("title").jsonPrimitive.content)
            assertEquals(originals[index].toText().take(12_000), result.getValue("snippet").jsonPrimitive.content)
            assertEquals("${conversation.id}/${originals[index].id}", result.getValue("source_ref").jsonPrimitive.content)
            assertEquals("PRIVATE_CHAT", result.getValue("sourceKind").jsonPrimitive.content)
            assertSame(conversation, reads[index].first)
            assertSame(originals[index], reads[index].second)
            assertTrue(reads[index].second.toText().length > 12_000)
        }
        assertFalse(results.toString().contains("秘密"))
        assertEquals(2, reads.size)
    }

    @Test
    fun `分页偏移按原始命中数前进而不是已验证数量`() = runBlocking {
        val conversation = conversation(rejectedCount = 49, visibleCount = 3)
        val results = tools(conversation).search { put("limit", 3) }

        assertEquals(listOf(0, 50), offsets)
        assertEquals(3, results.size)
        assertEquals(3, reads.size)
    }

    @Test
    fun `整页无效结果耗尽后请求空页并结束`() = runBlocking {
        val conversation = conversation(rejectedCount = 100, visibleCount = 0)
        assertTrue(tools(conversation).search().isEmpty())
        assertEquals(listOf(0, 50, 100), offsets)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `不足一页的无效尾页立即结束`() = runBlocking {
        val conversation = conversation(rejectedCount = 51, visibleCount = 0)
        assertTrue(tools(conversation).search().isEmpty())
        assertEquals(listOf(0, 50), offsets)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `首页验证结果达到限制时不再翻页`() = runBlocking {
        val conversation = conversation(rejectedCount = 0, visibleCount = 51)
        assertEquals(50, tools(conversation).search { put("limit", 999) }.size)
        assertEquals(listOf(0), offsets)
        assertEquals(50, reads.size)
    }

    @Test
    fun `模型偏移和权限参数不能改变任何一页的精确授权范围`() = runBlocking {
        val excluded = conversation(rejectedCount = 0, visibleCount = 1)
        val speaker = UIMessage.assistant("授权群的真实正文")
        val groupConversation = Conversation(
            assistantId = otherAssistantId,
            title = "授权群",
            messageNodes = listOf(MessageNode.of(speaker)),
            config = ConversationConfig(isGroupChat = true, speakerMap = mapOf(speaker.id to otherAssistantId)),
        )
        val group = GroupChat(memberIds = listOf(otherAssistantId, assistantId), conversationId = groupConversation.id)
        val hits = List(50) { excluded.hits().single() } + groupConversation.hits()
        val loadCalls = mutableListOf<Uuid>()
        val tools = createScopedMemoryConversationTools(
            assistantId = assistantId,
            allowedGroups = listOf(group),
            allGroupConversationIds = setOf(groupConversation.id),
            getConversationsOfAssistant = { listOf(excluded) },
            searchMessages = { _, _ -> error("不应调用旧入口") },
            getConversationById = { id ->
                loadCalls += id
                groupConversation.takeIf { it.id == id }
            },
            onRead = { source, messages -> messages.forEach { reads += source to it } },
            allowPrivateChats = false,
            searchMessagesPage = { _, ids, offset ->
                scopes += ids
                offsets += offset
                hits.drop(offset).take(50)
            },
        )
        val result = tools.search {
            put("offset", 9_999)
            put("assistantId", otherAssistantId.toString())
            put("conversation_id", excluded.id.toString())
            put("scope", "all")
            put("useGlobalMemory", true)
        }.single().jsonObject

        assertEquals(listOf(0, 50), offsets)
        assertEquals(listOf(setOf(groupConversation.id), setOf(groupConversation.id)), scopes)
        assertEquals(listOf(groupConversation.id), loadCalls)
        assertEquals(groupConversation.id.toString(), result.getValue("conversation_id").jsonPrimitive.content)
        assertEquals(group.id.toString(), result.getValue("groupId").jsonPrimitive.content)
        assertEquals("GROUP_CHAT", result.getValue("sourceKind").jsonPrimitive.content)
        assertEquals(otherAssistantId.toString(), result.getValue("speakerId").jsonPrimitive.content)
        assertEquals("other", result.getValue("speaker_relation").jsonPrimitive.content)
        assertSame(speaker, reads.single().second)
        val schema = tools.first().parameters() as InputSchema.Obj
        assertEquals(setOf("query", "limit"), schema.properties.keys)
    }

    @Test
    fun `旧两参数搜索回调保持单次调用兼容`() = runBlocking {
        val conversation = conversation(rejectedCount = 50, visibleCount = 1)
        var calls = 0
        val tools = createScopedMemoryConversationTools(
            assistantId = assistantId,
            allowedGroups = emptyList(),
            allGroupConversationIds = emptySet(),
            getConversationsOfAssistant = { listOf(conversation) },
            searchMessages = { _, ids ->
                calls++
                assertEquals(setOf(conversation.id), ids)
                conversation.hits().take(50)
            },
            getConversationById = { conversation },
        )

        assertTrue(tools.search().isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun `空候选范围不调用任何分页搜索`() = runBlocking {
        val tools = createScopedMemoryConversationTools(
            assistantId = assistantId,
            allowedGroups = emptyList(),
            allGroupConversationIds = emptySet(),
            getConversationsOfAssistant = { emptyList() },
            searchMessages = { _, _ -> error("空候选不应搜索") },
            getConversationById = { error("空候选不应读取") },
            onRead = { _, _ -> error("空候选不应登记来源") },
            searchMessagesPage = { _, _, _ -> error("空候选不应分页搜索") },
        )
        assertTrue(tools.search { put("offset", 50) }.isEmpty())
    }
}
