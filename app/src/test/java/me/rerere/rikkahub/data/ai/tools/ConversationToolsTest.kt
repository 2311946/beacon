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
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationToolsTest {
    private val assistantA = Uuid.random()
    private val assistantB = Uuid.random()
    private val assistantC = Uuid.random()
    private val searchCalls = mutableListOf<Pair<String, Set<Uuid>>>()
    private val readCalls = mutableListOf<Uuid>()
    private var recentCall: Pair<Uuid, Int>? = null
    private val conversations = listOf(
        conversation(assistantA, "甲的私聊"),
        conversation(assistantB, "乙的私聊"),
        conversation(assistantC, "丙的私聊"),
    )

    private fun conversation(assistantId: Uuid, title: String) = Conversation(
        assistantId = assistantId,
        title = title,
        messageNodes = emptyList(),
    ).updateCurrentMessages(listOf(UIMessage.user("共同关键词 $title")))

    private suspend fun search(query: String, scope: Set<Uuid>): List<MessageSearchResult> {
        searchCalls += query to scope
        return conversations.filter { it.assistantId in scope }.map {
            MessageSearchResult(
                nodeId = "node-${it.id}",
                messageId = it.currentMessages.single().id.toString(),
                conversationId = it.id.toString(),
                title = it.title,
                updateAt = Instant.EPOCH,
                snippet = "[$query] ${it.title}",
            )
        }
    }

    private fun chatTools(assistantId: Uuid = assistantA): List<Tool> = createConversationTools(
        assistantId = assistantId,
        getRecentConversations = { id, limit ->
            recentCall = id to limit
            conversations.filter { it.assistantId == id }.take(limit)
        },
        searchMessages = ::search,
    )

    private fun memoryTools(scope: Set<Uuid>): List<Tool> = createMemoryConversationTools(
        assistantIds = scope,
        searchMessages = ::search,
        getConversationById = { id ->
            readCalls += id
            conversations.find { it.id == id }
        },
    )

    private suspend fun List<Tool>.call(name: String, args: JsonObjectBuilder.() -> Unit): String =
        (single { it.name == name }.execute(buildJsonObject(args)).single() as UIMessagePart.Text).text

    private suspend fun List<Tool>.searchResults(args: JsonObjectBuilder.() -> Unit = {}): JsonArray =
        Json.parseToJsonElement(call("conversation_search") {
            put("query", "共同关键词")
            args()
        }).jsonArray

    private fun JsonArray.conversationIds() = map { it.jsonObject.getValue("conversation_id").jsonPrimitive.content }

    private val observedReads = mutableListOf<Pair<Conversation, List<UIMessage>>>()

    private fun scopedTools(
        data: List<Conversation>,
        groups: List<GroupChat> = emptyList(),
        allowPrivateChats: Boolean = true,
        knownGroups: Set<Uuid> = groups.mapNotNull { it.conversationId }.toSet(),
        load: suspend (Uuid) -> Conversation? = { id -> data.find { it.id == id } },
        hits: List<MessageSearchResult> = data.flatMap { conversation ->
            conversation.messageNodes.flatMap { node -> node.messages.map { message ->
                MessageSearchResult(
                    nodeId = node.id.toString(),
                    messageId = message.id.toString(),
                    conversationId = conversation.id.toString(),
                    title = "过时标题",
                    updateAt = Instant.EPOCH,
                    snippet = "禁止泄露的旧分支片段",
                )
            } }
        },
    ): List<Tool> = createScopedMemoryConversationTools(
        assistantId = assistantA,
        allowedGroups = groups,
        allGroupConversationIds = knownGroups,
        getConversationsOfAssistant = { id ->
            assertEquals(assistantA, id)
            data.map { it.copy(messageNodes = emptyList()) }
        },
        searchMessages = { query, ids ->
            searchCalls += query to ids
            // 故意返回范围外的命中，验证工具不信任索引响应。
            hits
        },
        getConversationById = { id -> readCalls += id; load(id) },
        onRead = { conversation, messages -> observedReads += conversation to messages },
        allowPrivateChats = allowPrivateChats,
    )

    private suspend fun List<Tool>.read(conversation: Conversation, offset: Int = 0, limit: Int = 40) =
        Json.parseToJsonElement(call("conversation_read") {
            put("conversation_id", conversation.id.toString())
            put("offset", offset)
            put("limit", limit)
        }).jsonObject

    @Test
    fun `scoped tools authorize group even when first member and owner are not target`() = runBlocking {
        val user = UIMessage.user("群用户正文")
        val self = UIMessage.assistant("甲群正文")
        val other = UIMessage.assistant("乙群正文")
        val unknown = UIMessage.assistant("未记录发言人正文")
        val groupConversation = Conversation(
            assistantId = assistantB,
            title = "授权群",
            messageNodes = listOf(user, self, other, unknown).map(MessageNode::of),
            config = ConversationConfig(
                isGroupChat = true,
                speakerMap = mapOf(self.id to assistantA, other.id to assistantB),
            ),
        )
        val group = GroupChat(memberIds = listOf(assistantB, assistantA), conversationId = groupConversation.id)
        val tools = scopedTools(listOf(groupConversation), listOf(group))
        val results = tools.searchResults()
        assertEquals(setOf(groupConversation.id), searchCalls.single().second)
        assertEquals(listOf("user", "self", "other", "unknown"), results.map {
            it.jsonObject.getValue("speaker_relation").jsonPrimitive.content
        })
        results.forEach {
            assertEquals("GROUP_CHAT", it.jsonObject.getValue("sourceKind").jsonPrimitive.content)
            assertEquals(group.id.toString(), it.jsonObject.getValue("groupId").jsonPrimitive.content)
        }
        assertEquals("null", results.last().jsonObject.getValue("speakerId").toString())
        val messages = tools.read(groupConversation).getValue("messages").jsonArray
        assertEquals(listOf(user, self, other, unknown).map { it.id.toString() }, messages.map {
            it.jsonObject.getValue("messageId").jsonPrimitive.content
        })
        assertEquals(5, observedReads.size)
        assertEquals(listOf(user.id, self.id, other.id, unknown.id), observedReads.last().second.map { it.id })
    }

    @Test
    fun `scoped tools reject other private chats and groups without explicit target membership`() = runBlocking {
        val own = conversations[0]
        val otherPrivate = conversations[1]
        val known = conversation(assistantA, "已知但未授权群")
        val unknown = conversation(assistantA, "仅配置标记的未知群").copy(config = ConversationConfig(isGroupChat = true))
        val nonMember = conversation(assistantB, "目标不在群中")
        val group = GroupChat(memberIds = listOf(assistantB), conversationId = nonMember.id)
        val tools = scopedTools(
            listOf(own, otherPrivate, known, unknown, nonMember),
            groups = listOf(group),
            knownGroups = setOf(known.id, nonMember.id),
        )
        assertEquals(listOf(own.id.toString()), tools.searchResults().conversationIds())
        assertEquals(setOf(own.id), searchCalls.single().second)
        listOf(otherPrivate, known, unknown, nonMember).forEach { rejected ->
            assertThrows(IllegalStateException::class.java) { runBlocking { tools.read(rejected) } }
        }
        assertEquals(listOf(own.id), observedReads.map { it.first.id })
    }

    @Test
    fun `personal group library cannot query private chats assigned to global memory`() = runBlocking<Unit> {
        val own = conversations[0]
        val groupConversation = conversation(assistantB, "个人库授权群")
            .copy(config = ConversationConfig(isGroupChat = true))
        val group = GroupChat(memberIds = listOf(assistantA, assistantB), conversationId = groupConversation.id)
        val tools = scopedTools(conversations + groupConversation, listOf(group), allowPrivateChats = false)
        assertEquals(listOf(groupConversation.id.toString()), tools.searchResults().conversationIds())
        assertEquals(setOf(groupConversation.id), searchCalls.single().second)
        assertThrows(IllegalStateException::class.java) { runBlocking { tools.read(own) } }
        assertEquals(groupConversation.id.toString(), tools.read(groupConversation).getValue("conversation_id").jsonPrimitive.content)
        assertTrue(observedReads.all { it.first.id == groupConversation.id })
    }

    @Test
    fun `scoped tool arguments cannot expand exact conversation scope`() = runBlocking {
        val groupConversation = conversation(assistantB, "授权群").copy(config = ConversationConfig(isGroupChat = true))
        val group = GroupChat(memberIds = listOf(assistantB, assistantA), conversationId = groupConversation.id)
        val tools = scopedTools(conversations + groupConversation, listOf(group))
        val results = tools.searchResults {
            put("assistant_id", assistantB.toString())
            put("assistantId", assistantC.toString())
            put("conversation_id", conversations[1].id.toString())
            put("groupId", Uuid.random().toString())
            put("speakerId", assistantB.toString())
            put("sourceKind", "PRIVATE_CHAT")
            put("scope", "all")
            put("useGlobalMemory", true)
        }
        assertEquals(setOf(conversations[0].id, groupConversation.id), searchCalls.single().second)
        assertEquals(setOf(conversations[0].id.toString(), groupConversation.id.toString()), results.conversationIds().toSet())
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                tools.call("conversation_read") {
                    put("conversation_id", conversations[1].id.toString())
                    put("assistant_id", assistantB.toString())
                    put("groupId", group.id.toString())
                }
            }
        }
        assertEquals(setOf("query", "limit"), (tools.first().parameters() as InputSchema.Obj).properties.keys)
    }

    @Test
    fun `scoped search verifies current branch and never returns stale snippets or synthetic messages`() = runBlocking {
        val old = UIMessage.assistant("旧分支秘密")
        val real = UIMessage.assistant("当前真实正文")
        val synthetic = UIMessage.user("合成秘密").copy(isSynthetic = true)
        val checkpoint = UIMessage.user("检查点秘密").copy(isContextCheckpoint = true)
        val empty = UIMessage.assistant("")
        val conversation = Conversation(
            assistantId = assistantA,
            messageNodes = listOf(
                MessageNode(messages = listOf(old, real), selectIndex = 1),
                MessageNode.of(synthetic), MessageNode.of(checkpoint), MessageNode.of(empty),
            ),
        )
        val tools = scopedTools(listOf(conversation))
        val result = tools.searchResults().single().jsonObject
        assertEquals(real.id.toString(), result.getValue("messageId").jsonPrimitive.content)
        assertEquals("当前真实正文", result.getValue("snippet").jsonPrimitive.content)
        assertEquals("PRIVATE_CHAT", result.getValue("sourceKind").jsonPrimitive.content)
        assertEquals("self", result.getValue("speaker_relation").jsonPrimitive.content)
        val read = tools.read(conversation)
        val returned = read.getValue("messages").jsonArray.single().jsonObject
        assertEquals("0", returned.getValue("position").jsonPrimitive.content)
        assertEquals("当前真实正文", returned.getValue("text").jsonPrimitive.content)
        assertEquals("4", read.getValue("total").jsonPrimitive.content)
        assertEquals(listOf(real.id, real.id), observedReads.flatMap { it.second }.map { it.id })
        assertTrue(tools.read(conversation, offset = 1).getValue("messages").jsonArray.isEmpty())
        assertEquals(2, observedReads.size)
        assertFalse(result.toString().contains("秘密"))
    }

    @Test
    fun `scoped reads preserve dialogue positions while skipping synthetic checkpoints`() = runBlocking {
        val synthetic = UIMessage.user("合成").copy(isSynthetic = true)
        val checkpoint = UIMessage.user("检查点").copy(isContextCheckpoint = true)
        val user = UIMessage.user("可见用户")
        val assistant = UIMessage.assistant("可见助手")
        val system = user.copy(role = MessageRole.SYSTEM)
        val conversation = Conversation(
            assistantId = assistantA,
            messageNodes = listOf(system, synthetic, user, checkpoint, assistant).map(MessageNode::of),
        )
        val result = scopedTools(listOf(conversation)).read(conversation, offset = 1, limit = 3)
        val messages = result.getValue("messages").jsonArray
        assertEquals(listOf("1", "3"), messages.map { it.jsonObject.getValue("position").jsonPrimitive.content })
        assertEquals(listOf("user", "self"), messages.map { it.jsonObject.getValue("speaker_relation").jsonPrimitive.content })
        assertEquals(listOf(user.id, assistant.id), observedReads.single().second.map { it.id })
    }

    @Test
    fun `scoped search rechecks changed ownership and group config after candidate enumeration`() = runBlocking<Unit> {
        val own = conversations[0]
        val tools = scopedTools(listOf(own), load = { own.copy(assistantId = assistantB) })
        assertTrue(tools.searchResults().isEmpty())
        assertEquals(listOf(own.id), readCalls)
        assertTrue(observedReads.isEmpty())
        val groupChanged = scopedTools(listOf(own), load = { own.copy(config = ConversationConfig(isGroupChat = true)) })
        assertTrue(groupChanged.searchResults().isEmpty())
        assertThrows(IllegalStateException::class.java) { runBlocking { groupChanged.read(own) } }
    }

    @Test
    fun `scoped tools with no authorized conversations never search or report evidence`() = runBlocking {
        val group = GroupChat(memberIds = listOf(assistantB), conversationId = conversations[1].id)
        val tools = scopedTools(conversations.drop(1), groups = listOf(group))
        assertTrue(tools.searchResults { put("scope", "all") }.isEmpty())
        assertTrue(searchCalls.isEmpty())
        assertTrue(observedReads.isEmpty())
        assertThrows(IllegalStateException::class.java) { runBlocking { tools.read(conversations[1]) } }
        assertTrue(observedReads.isEmpty())
    }

    @Test
    fun `scoped onRead receives original displayed message and no blank evidence`() = runBlocking {
        val long = UIMessage.user("正文".repeat(8_000))
        val blank = UIMessage.assistant("   ")
        val conversation = Conversation(assistantId = assistantA, messageNodes = listOf(long, blank).map(MessageNode::of))
        val tools = scopedTools(listOf(conversation))
        val search = tools.searchResults().single().jsonObject
        val read = tools.read(conversation).getValue("messages").jsonArray.single().jsonObject
        assertEquals(12_000, search.getValue("snippet").jsonPrimitive.content.length)
        assertEquals(12_000, read.getValue("text").jsonPrimitive.content.length)
        observedReads.forEach { (_, messages) ->
            assertEquals(listOf(long.id), messages.map { it.id })
            assertTrue(messages.single() === long)
            assertEquals(long.toText(), messages.single().toText())
        }
        assertEquals(2, observedReads.size)
    }

    private fun authorizedChatTools(
        data: List<Conversation>,
        settings: Settings? = null,
        load: suspend (Uuid) -> Conversation? = { id -> data.find { it.id == id } },
    ): List<Tool> = createConversationTools(
        assistantId = assistantA,
        settings = settings,
        getRecentConversations = { id, limit ->
            recentCall = id to limit
            // 故意包含范围外候选，验证普通入口也会复查 recent_chats。
            data.take(limit)
        },
        searchMessages = { query, ids ->
            searchCalls += query to ids
            data.flatMap { conversation ->
                conversation.messageNodes.flatMap { node ->
                    node.messages.map { message ->
                        MessageSearchResult(
                            nodeId = node.id.toString(),
                            messageId = message.id.toString(),
                            conversationId = conversation.id.toString(),
                            title = "过时标题",
                            updateAt = Instant.EPOCH,
                            snippet = "禁止泄露的旧索引正文",
                        )
                    }
                }
            }
        },
        getConversationById = { id -> readCalls += id; load(id) },
    )

    @Test
    fun `ordinary authorization requires owner and unique explicit group membership`() = runBlocking {
        val own = conversations[0]
        val allowed = conversation(assistantA, "授权群").copy(config = ConversationConfig(isGroupChat = true))
        val linked = conversation(assistantA, "无配置标记的授权群")
        val left = conversation(assistantA, "owner已退群")
        val unknown = conversation(assistantA, "无关联群").copy(config = ConversationConfig(isGroupChat = true))
        val ambiguous = conversation(assistantA, "歧义关联群")
        val otherOwner = conversation(assistantB, "目标为成员但不是owner")
            .copy(config = ConversationConfig(isGroupChat = true))
        val groups = listOf(
            GroupChat(memberIds = listOf(assistantB, assistantA), conversationId = allowed.id),
            GroupChat(memberIds = listOf(assistantA), conversationId = linked.id),
            GroupChat(memberIds = listOf(assistantB), conversationId = left.id),
            GroupChat(memberIds = listOf(assistantA), conversationId = ambiguous.id),
            GroupChat(memberIds = listOf(assistantA, assistantB), conversationId = ambiguous.id),
            GroupChat(memberIds = listOf(assistantB, assistantA), conversationId = otherOwner.id),
        )
        val tools = authorizedChatTools(
            listOf(own, allowed, linked, left, unknown, ambiguous, otherOwner, conversations[1]),
            Settings(groupChats = groups),
        )
        val results = tools.searchResults {
            put("assistant_id", assistantB.toString())
            put("assistantIds", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(assistantB.toString()))))
            put("groupId", groups.last().id.toString())
            put("conversation_id", otherOwner.id.toString())
            put("scope", "all")
            put("useGlobalMemory", true)
        }
        val expected = listOf(own, allowed, linked).map { it.id.toString() }
        assertEquals(expected, results.conversationIds())
        assertEquals(listOf("共同关键词" to setOf(assistantA)), searchCalls)
        val recent = Json.parseToJsonElement(tools.call("recent_chats") {
            put("assistant_id", assistantB.toString())
            put("scope", "all")
        }).jsonArray
        assertEquals(expected, recent.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(assistantA to 10, recentCall)
        assertFalse(results.toString().contains("禁止泄露"))
        assertEquals(setOf("query", "limit"), (tools.last().parameters() as InputSchema.Obj).properties.keys)
    }

    @Test
    fun `ordinary tools without settings reject config only groups`() = runBlocking {
        val group = conversation(assistantA, "无快照群").copy(config = ConversationConfig(isGroupChat = true))
        val tools = authorizedChatTools(listOf(conversations[0], group))
        assertEquals(listOf(conversations[0].id.toString()), tools.searchResults().conversationIds())
        val recent = Json.parseToJsonElement(tools.call("recent_chats") {}).jsonArray
        assertEquals(listOf(conversations[0].id.toString()), recent.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
        })
        assertEquals(setOf(assistantA), searchCalls.single().second)
    }

    @Test
    fun `ordinary tools snapshot group associations and members`() = runBlocking {
        val groupConversation = conversation(assistantA, "快照授权群")
        val members = mutableListOf(assistantA)
        val groups = mutableListOf(GroupChat(memberIds = members, conversationId = groupConversation.id))
        val tools = authorizedChatTools(listOf(groupConversation), Settings(groupChats = groups))
        members.clear()
        groups.clear()
        groups += GroupChat(memberIds = listOf(assistantB), conversationId = groupConversation.id)
        assertEquals(listOf(groupConversation.id.toString()), tools.searchResults().conversationIds())
        assertEquals(1, Json.parseToJsonElement(tools.call("recent_chats") {}).jsonArray.size)
    }

    @Test
    fun `ordinary search returns only current visible text and filters before limit`() = runBlocking {
        val old = UIMessage.assistant("旧分支秘密")
        val visible = UIMessage.assistant("当前正文").copy(parts = listOf(
            UIMessagePart.Reasoning("推理秘密"),
            UIMessagePart.Text("当前正文"),
            UIMessagePart.Text("第二段"),
        ))
        val synthetic = UIMessage.user("合成秘密").copy(isSynthetic = true)
        val checkpoint = UIMessage.user("检查点秘密").copy(isContextCheckpoint = true)
        val system = UIMessage.user("系统秘密").copy(role = MessageRole.SYSTEM)
        val nonText = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Reasoning("仅推理秘密")))
        val conversation = Conversation(
            assistantId = assistantA,
            title = "当前标题",
            messageNodes = listOf(synthetic, checkpoint, system, nonText, UIMessage.user("  "))
                .map(MessageNode::of) + MessageNode(messages = listOf(old, visible), selectIndex = 1),
        )
        val tools = authorizedChatTools(listOf(conversation))
        val result = tools.searchResults { put("limit", 1) }.single().jsonObject
        assertEquals("当前正文\n第二段", result.getValue("snippet").jsonPrimitive.content)
        assertEquals("当前标题", result.getValue("title").jsonPrimitive.content)
        assertEquals(setOf("conversation_id", "title", "snippet", "date"), result.keys)
        assertFalse(result.toString().contains("秘密"))
        assertEquals(setOf(assistantA), searchCalls.single().second)
    }

    @Test
    fun `ordinary search and recent recheck loaded ownership group config and identity`() = runBlocking {
        val own = conversations[0]
        val replacements = listOf(
            own.copy(assistantId = assistantB),
            own.copy(config = ConversationConfig(isGroupChat = true)),
            own.copy(id = Uuid.random()),
            null,
        )
        replacements.forEach { replacement ->
            val tools = authorizedChatTools(listOf(own), load = { replacement })
            assertTrue(tools.searchResults().isEmpty())
            assertTrue(Json.parseToJsonElement(tools.call("recent_chats") {}).jsonArray.isEmpty())
        }
        assertEquals(List(replacements.size * 2) { own.id }, readCalls)
        assertTrue(searchCalls.all { it.second == setOf(assistantA) })
    }

    @Test
    fun `ordinary search is limited to its current assistant`() = runBlocking {
        val result = chatTools().searchResults()

        assertEquals(listOf("共同关键词" to setOf(assistantA)), searchCalls)
        assertEquals(listOf(conversations[0].id.toString()), result.conversationIds())
        assertFalse(result.toString().contains("乙的私聊"))
        assertFalse(result.toString().contains("丙的私聊"))
    }

    @Test
    fun `each ordinary tool instance keeps its own assistant scope`() = runBlocking {
        val toolsA = chatTools(assistantA)
        val toolsB = chatTools(assistantB)

        assertEquals(listOf(conversations[0].id.toString()), toolsA.searchResults().conversationIds())
        assertEquals(listOf(conversations[1].id.toString()), toolsB.searchResults().conversationIds())
        assertEquals(listOf(conversations[0].id.toString()), toolsA.searchResults().conversationIds())
        assertEquals(listOf(setOf(assistantA), setOf(assistantB), setOf(assistantA)), searchCalls.map { it.second })
    }

    @Test
    fun `model supplied assistant and global flags cannot widen ordinary search`() = runBlocking {
        val result = chatTools().searchResults {
            put("assistantId", assistantB.toString())
            put("assistant_id", assistantB.toString())
            put("assistantIds", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(assistantB.toString()))))
            put("useGlobalMemory", true)
            put("scope", "all")
        }

        assertEquals(setOf(assistantA), searchCalls.single().second)
        assertEquals(listOf(conversations[0].id.toString()), result.conversationIds())
    }

    @Test
    fun `search schema exposes no assistant or scope override`() {
        val schemas = listOf(chatTools(), memoryTools(setOf(assistantA, assistantB))).map { tools ->
            tools.single { it.name == "conversation_search" }.parameters() as InputSchema.Obj
        }
        schemas.forEach {
            assertEquals(setOf("query", "limit"), it.properties.keys)
            assertEquals(listOf("query"), it.required)
        }
    }

    @Test
    fun `search preserves query and result payload`() = runBlocking {
        val result = chatTools().searchResults { put("query", "宣宣 喜欢的茶") }.single().jsonObject

        assertEquals("宣宣 喜欢的茶", searchCalls.single().first)
        assertEquals(setOf("conversation_id", "title", "snippet", "date"), result.keys)
        assertEquals("甲的私聊", result.getValue("title").jsonPrimitive.content)
        assertEquals("[宣宣 喜欢的茶] 甲的私聊", result.getValue("snippet").jsonPrimitive.content)
        assertTrue(result.getValue("date").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `search requires query before calling repository`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { chatTools().call("conversation_search") {} }
        }
        assertTrue(searchCalls.isEmpty())
    }

    @Test
    fun `search result limits keep default minimum and maximum`() = runBlocking {
        val tools = createConversationTools(
            assistantId = assistantA,
            getRecentConversations = { _, _ -> emptyList() },
            searchMessages = { query, scope ->
                val result = search(query, scope).single()
                List(60) { result.copy(messageId = "$it") }
            },
        )

        assertEquals(15, tools.searchResults().size)
        assertEquals(1, tools.searchResults { put("limit", 0) }.size)
        assertEquals(2, tools.searchResults { put("limit", 2) }.size)
        assertEquals(50, tools.searchResults { put("limit", 9999) }.size)
        assertTrue(searchCalls.all { it.second == setOf(assistantA) })
    }

    @Test
    fun `recent chats still uses current assistant and bounded limit`() = runBlocking {
        val tools = chatTools()
        val result = Json.parseToJsonElement(tools.call("recent_chats") {}).jsonArray

        assertEquals(assistantA to 10, recentCall)
        assertEquals(conversations[0].id.toString(), result.single().jsonObject.getValue("id").jsonPrimitive.content)
        tools.call("recent_chats") { put("limit", 999); put("assistant_id", assistantB.toString()) }
        assertEquals(assistantA to 30, recentCall)
        tools.call("recent_chats") { put("limit", 0) }
        assertEquals(assistantA to 1, recentCall)
        assertTrue(searchCalls.isEmpty())
    }

    @Test
    fun `background search preserves its explicitly allowed assistant set`() = runBlocking {
        val result = memoryTools(setOf(assistantA, assistantB)).searchResults()

        assertEquals(setOf(assistantA, assistantB), searchCalls.single().second)
        assertEquals(conversations.take(2).map { it.id.toString() }, result.conversationIds())
        assertFalse(result.toString().contains("丙的私聊"))
    }

    @Test
    fun `ordinary search with no matches does not retry with a wider scope`() = runBlocking {
        val assistantWithoutChats = Uuid.random()
        val result = chatTools(assistantWithoutChats).searchResults()

        assertTrue(result.isEmpty())
        assertEquals(listOf("共同关键词" to setOf(assistantWithoutChats)), searchCalls)
    }

    @Test
    fun `model arguments cannot widen background search whitelist`() = runBlocking {
        val result = memoryTools(setOf(assistantA, assistantB)).searchResults {
            put("assistant_id", assistantC.toString())
            put("scope", "all")
        }

        assertEquals(setOf(assistantA, assistantB), searchCalls.single().second)
        assertEquals(conversations.take(2).map { it.id.toString() }, result.conversationIds())
    }

    @Test
    fun `empty background scope returns no results without searching`() = runBlocking {
        val result = memoryTools(emptySet()).searchResults { put("scope", "all") }

        assertTrue(result.isEmpty())
        assertTrue(searchCalls.isEmpty())
    }

    @Test
    fun `background tools snapshot allowed scope for both search and read`() = runBlocking {
        val scope = mutableSetOf(assistantA)
        val tools = memoryTools(scope)
        scope.clear()
        scope += assistantB

        assertEquals(listOf(conversations[0].id.toString()), tools.searchResults().conversationIds())
        val allowed = Json.parseToJsonElement(tools.call("conversation_read") {
            put("conversation_id", conversations[0].id.toString())
        }).jsonObject
        assertEquals("甲的私聊", allowed.getValue("title").jsonPrimitive.content)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                tools.call("conversation_read") { put("conversation_id", conversations[1].id.toString()) }
            }
        }
        assertEquals(setOf(assistantA), searchCalls.single().second)
    }

    @Test
    fun `background read allows authorized conversations`() = runBlocking {
        val result = Json.parseToJsonElement(memoryTools(setOf(assistantA, assistantB)).call("conversation_read") {
            put("conversation_id", conversations[1].id.toString())
        }).jsonObject

        assertEquals("乙的私聊", result.getValue("title").jsonPrimitive.content)
        assertEquals("1", result.getValue("total").jsonPrimitive.content)
        assertEquals(
            "共同关键词 乙的私聊",
            result.getValue("messages").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun `background read rejects conversations outside scope`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                memoryTools(setOf(assistantA)).call("conversation_read") {
                    put("conversation_id", conversations[1].id.toString())
                    put("assistant_id", assistantB.toString())
                }
            }
        }
    }

    @Test
    fun `background read with empty scope never returns a conversation`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                memoryTools(emptySet()).call("conversation_read") {
                    put("conversation_id", conversations[0].id.toString())
                }
            }
        }
    }

    @Test
    fun `malformed conversation id is rejected before loading`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                memoryTools(setOf(assistantA)).call("conversation_read") { put("conversation_id", "not-a-uuid") }
            }
        }
        assertTrue(readCalls.isEmpty())
    }
}
