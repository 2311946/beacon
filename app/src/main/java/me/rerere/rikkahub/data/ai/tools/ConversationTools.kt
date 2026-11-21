package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.model.MemorySourceKind
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
    settings: Settings? = null,
): List<Tool> = createConversationTools(
    assistantId = assistantId,
    settings = settings,
    getRecentConversations = conversationRepo::getRecentConversations,
    searchMessages = conversationRepo::searchMessagesOfAssistants,
    getConversationById = conversationRepo::getConversationById,
)

internal fun createConversationTools(
    assistantId: Uuid,
    settings: Settings?,
    getRecentConversations: suspend (Uuid, Int) -> List<Conversation>,
    searchMessages: suspend (String, Set<Uuid>) -> List<MessageSearchResult>,
    getConversationById: suspend (Uuid) -> Conversation?,
): List<Tool> {
    // 群配置只用来收紧原有 owner 范围，不能借群成员关系扩大到其他 owner。
    val groupsByConversation = settings?.groupChats.orEmpty()
        .filter { it.conversationId != null }
        .map { it.copy(memberIds = it.memberIds.toList()) }
        .groupBy { requireNotNull(it.conversationId) }

    fun isAllowed(conversation: Conversation): Boolean {
        if (conversation.assistantId != assistantId) return false
        val groups = groupsByConversation[conversation.id]
        return if (groups != null) {
            groups.singleOrNull()?.let { assistantId in it.memberIds } == true
        } else {
            conversation.config?.isGroupChat != true
        }
    }

    return createConversationTools(
        assistantId = assistantId,
        getRecentConversations = { id, limit ->
            getRecentConversations(id, limit).mapNotNull { candidate ->
                getConversationById(candidate.id)?.takeIf { it.id == candidate.id && isAllowed(it) }
            }
        },
        searchMessages = { query, ids ->
            searchMessages(query, ids).mapNotNull { result ->
                val id = runCatching { Uuid.parse(result.conversationId) }.getOrNull()
                    ?: return@mapNotNull null
                val conversation = getConversationById(id)?.takeIf { it.id == id && isAllowed(it) }
                    ?: return@mapNotNull null
                val node = conversation.messageNodes.singleOrNull { it.id.toString() == result.nodeId }
                    ?: return@mapNotNull null
                val message = node.messages.getOrNull(node.selectIndex)?.takeIf {
                    it.id.toString() == result.messageId &&
                        (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) &&
                        !it.isSynthetic && !it.isContextCheckpoint
                } ?: return@mapNotNull null
                val text = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }.take(READ_MESSAGE_LENGTH)
                if (text.isBlank()) return@mapNotNull null
                result.copy(title = conversation.title, updateAt = conversation.updateAt, snippet = text)
            }
        },
    )
}

internal fun createConversationTools(
    assistantId: Uuid,
    getRecentConversations: suspend (Uuid, Int) -> List<Conversation>,
    searchMessages: suspend (String, Set<Uuid>) -> List<MessageSearchResult>,
): List<Tool> = listOf(
    Tool(
        name = "recent_chats",
        description = """
            List the user's recent conversations with you to understand their preferences and ongoing topics.
            Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated.
            Use this when you need quick context about what the user has been discussing lately.
            Only titles and dates are returned; use `conversation_search` to look up the actual content.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of recent conversations to return (default: 10, max: 30)"
                        )
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
            val recent = getRecentConversations(assistantId, limit)
            val payload = buildJsonArray {
                recent.forEach { conversation ->
                    add(buildJsonObject {
                        put("id", conversation.id.toString())
                        put("title", conversation.title.ifBlank { "Untitled" })
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    // 作用域来自当前有效助手，不接受模型参数扩大到其他助手或全库。
    conversationSearchTool(setOf(assistantId), searchMessages),
)

private fun conversationSearchTool(
    assistantIds: Set<Uuid>,
    search: suspend (String, Set<Uuid>) -> List<MessageSearchResult>,
): Tool {
    // 固定创建工具时的可信范围；空集合拒绝搜索，不退回不限范围。
    val allowedAssistantIds = assistantIds.toSet()
    return Tool(
        name = "conversation_search",
        description = """
            Full-text search only within past conversations of the assistants authorized for this tool.
            The authorized scope is fixed by the app and cannot be expanded through tool arguments.
            Use focused keywords. Run multiple searches with different keywords if needed.
            Each result includes the conversation title, a snippet with matched keywords wrapped in [brackets], and the date.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to search for in past conversation messages")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of results to return (default: 15, max: 50)"
                        )
                    })
                },
                required = listOf("query")
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull
                ?: error("query is required")
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)
            val results = if (allowedAssistantIds.isEmpty()) emptyList() else search(query, allowedAssistantIds).take(limit)
            val payload = buildJsonArray {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("conversation_id", result.conversationId)
                        put("title", result.title.ifBlank { "Untitled" })
                        put("snippet", result.snippet)
                        put("date", result.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    )
}

private const val READ_DEFAULT_LIMIT = 20
private const val READ_MAX_LIMIT = 40
private const val READ_MESSAGE_LENGTH = 12_000

/**
 * 给后台记忆整理用的对话查询工具，只能看到 [assistantIds] 这些助手的对话：
 * 独立记忆库的助手不该读到别的助手的对话内容。
 */
fun createMemoryConversationTools(
    conversationRepo: ConversationRepository,
    assistantIds: Set<Uuid>,
): List<Tool> = createMemoryConversationTools(
    assistantIds = assistantIds,
    searchMessages = conversationRepo::searchMessagesOfAssistants,
    getConversationById = conversationRepo::getConversationById,
)

internal fun createMemoryConversationTools(
    assistantIds: Set<Uuid>,
    searchMessages: suspend (String, Set<Uuid>) -> List<MessageSearchResult>,
    getConversationById: suspend (Uuid) -> Conversation?,
): List<Tool> {
    val allowedAssistantIds = assistantIds.toSet()
    return listOf(
        conversationSearchTool(allowedAssistantIds, searchMessages),
        Tool(
            name = "conversation_read",
            description = """
                Read the messages of one conversation in full, by position.
                Use it when a message you were given was cut off, or a search snippet needs its surrounding context.
                Positions are the #numbers shown next to each message.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("conversation_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Id of the conversation")
                        })
                        put("offset", buildJsonObject {
                            put("type", "integer")
                            put("description", "Position of the first message to return (default: 0)")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put(
                                "description",
                                "Maximum number of messages to return (default: $READ_DEFAULT_LIMIT, max: $READ_MAX_LIMIT)"
                            )
                        })
                    },
                    required = listOf("conversation_id")
                )
            },
            execute = {
                val args = it.jsonObject
                val conversation = args["conversation_id"]?.jsonPrimitive?.contentOrNull
                    ?.let { id -> runCatching { Uuid.parse(id) }.getOrNull() }
                    ?.let { id -> getConversationById(id) }
                    ?.takeIf { conversation -> conversation.assistantId in allowedAssistantIds }
                    ?: error("conversation not found")
                val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: READ_DEFAULT_LIMIT).coerceIn(1, READ_MAX_LIMIT)
                val messages = conversation.dialogueMessages()
                val payload = buildJsonObject {
                    put("title", conversation.title.ifBlank { "Untitled" })
                    put("total", messages.size)
                    put("messages", buildJsonArray {
                        messages.drop(offset).take(limit).forEachIndexed { index, message ->
                            add(buildJsonObject {
                                put("position", offset + index)
                                put("role", message.role.name)
                                put("date", message.createdAt.date.toString())
                                put("text", message.toText().take(READ_MESSAGE_LENGTH))
                            })
                        }
                    })
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        ),
    )
}

/** 后台整理只读目标助手的私聊和显式授权的群，不把群成员当作私聊白名单。 */
fun createScopedMemoryConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
    allowedGroups: List<GroupChat>,
    allGroupConversationIds: Set<Uuid>,
    onRead: suspend (Conversation, List<UIMessage>) -> Unit = { _, _ -> },
    allowPrivateChats: Boolean = true,
): List<Tool> = createScopedMemoryConversationTools(
    assistantId = assistantId,
    allowedGroups = allowedGroups,
    allGroupConversationIds = allGroupConversationIds,
    getConversationsOfAssistant = { id -> conversationRepo.getConversationsOfAssistant(id).first() },
    searchMessages = { query, ids -> conversationRepo.searchMessagesOfConversations(query, ids) },
    getConversationById = conversationRepo::getConversationById,
    onRead = onRead,
    allowPrivateChats = allowPrivateChats,
    searchMessagesPage = conversationRepo::searchMessagesOfConversations,
)

internal fun createScopedMemoryConversationTools(
    assistantId: Uuid,
    allowedGroups: List<GroupChat>,
    allGroupConversationIds: Set<Uuid>,
    getConversationsOfAssistant: suspend (Uuid) -> List<Conversation>,
    searchMessages: suspend (String, Set<Uuid>) -> List<MessageSearchResult>,
    getConversationById: suspend (Uuid) -> Conversation?,
    onRead: suspend (Conversation, List<UIMessage>) -> Unit = { _, _ -> },
    allowPrivateChats: Boolean = true,
    searchMessagesPage: (suspend (String, Set<Uuid>, Int) -> List<MessageSearchResult>)? = null,
): List<Tool> {
    val groups = allowedGroups.map { it.copy(memberIds = it.memberIds.toList()) }
    val knownGroupIds = allGroupConversationIds.toSet() + groups.mapNotNull { it.conversationId }
    // 先按会话排除歧义，再检查成员；不能把同一会话的多个群关联静默覆盖。
    val authorizedGroups = groups.filter { it.conversationId != null }
        .groupBy { requireNotNull(it.conversationId) }
        .mapNotNull { (id, matches) ->
            matches.singleOrNull()?.takeIf { assistantId in it.memberIds }?.let { id to it }
        }.toMap()

    fun isAllowed(conversation: Conversation): Boolean =
        conversation.id in authorizedGroups ||
            (allowPrivateChats && conversation.assistantId == assistantId && conversation.id !in knownGroupIds &&
                conversation.config?.isGroupChat != true)

    fun JsonObjectBuilder.putSource(conversation: Conversation, message: UIMessage) {
        val group = authorizedGroups[conversation.id]
        val speakerId = when {
            message.role == MessageRole.USER -> null
            group != null -> conversation.config?.speakerMap?.get(message.id)
            else -> conversation.assistantId
        }
        put("source_ref", "${conversation.id}/${message.id}")
        put("role", message.role.name)
        put("messageId", message.id.toString())
        put("sourceKind", if (group == null) MemorySourceKind.PRIVATE_CHAT.name else MemorySourceKind.GROUP_CHAT.name)
        put("groupId", group?.id?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
        put("speakerId", speakerId?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
        put("speaker_relation", when {
            message.role == MessageRole.USER -> "user"
            speakerId == null -> "unknown"
            speakerId == assistantId -> "self"
            else -> "other"
        })
    }

    return listOf(
        Tool(
            name = "conversation_search",
            description = "搜索目标助手的私聊与显式授权群聊。仅返回当前分支的真实消息及来源；范围不能通过参数扩大。",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "要搜索的关键词")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "结果数量，默认15，最多50")
                        })
                    },
                    required = listOf("query"),
                )
            },
            execute = {
                val args = it.jsonObject
                val query = args["query"]?.jsonPrimitive?.contentOrNull ?: error("query is required")
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)
                val candidates = getConversationsOfAssistant(assistantId)
                    .filter(::isAllowed).map { it.id }.toSet() + authorizedGroups.keys
                val entries = mutableListOf<JsonObject>()
                var searchOffset = 0
                while (candidates.isNotEmpty() && entries.size < limit) {
                    val results = searchMessagesPage?.invoke(query, candidates, searchOffset)
                        ?: searchMessages(query, candidates)
                    for (result in results) {
                        if (entries.size >= limit) break
                        val id = runCatching { Uuid.parse(result.conversationId) }.getOrNull() ?: continue
                        if (id !in candidates) continue
                        // 索引可能属于旧分支，也可能在搜索期间已变更归属，不能直接返回 snippet。
                        val conversation = getConversationById(id)?.takeIf { it.id == id && isAllowed(it) } ?: continue
                        val node = conversation.messageNodes.singleOrNull { it.id.toString() == result.nodeId } ?: continue
                        val message = node.messages.getOrNull(node.selectIndex)?.takeIf {
                            it.id.toString() == result.messageId &&
                                (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) &&
                                !it.isSynthetic && !it.isContextCheckpoint
                        } ?: continue
                        val text = message.memoryVisibleText()
                        if (text.isBlank()) continue
                        entries += buildJsonObject {
                            put("conversation_id", conversation.id.toString())
                            put("title", conversation.title.ifBlank { "Untitled" })
                            put("snippet", text)
                            put("date", conversation.updateAt.toLocalDate())
                            putSource(conversation, message)
                        }
                        // 显示可以截断，但来源哈希必须绑定这条实际展示了正文的原始消息。
                        onRead(conversation, listOf(message))
                    }
                    // 旧回调只能取一页；生产搜索逐页跳过无效命中，避免旧分支占满首页。
                    if (searchMessagesPage == null || results.size < 50) break
                    searchOffset += results.size
                }
                listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(JsonArray(entries))))
            },
        ),
        Tool(
            name = "conversation_read",
            description = "按位置读取授权会话当前分支的真实正文和来源。位置沿用用户与助手消息的编号，合成消息与检查点会跳过。",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("conversation_id", buildJsonObject {
                            put("type", "string")
                            put("description", "会话ID")
                        })
                        put("offset", buildJsonObject {
                            put("type", "integer")
                            put("description", "首条消息位置，默认0")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "读取数量，默认20，最多40")
                        })
                    },
                    required = listOf("conversation_id"),
                )
            },
            execute = {
                val args = it.jsonObject
                val id = args["conversation_id"]?.jsonPrimitive?.contentOrNull
                    ?.let { value -> runCatching { Uuid.parse(value) }.getOrNull() }
                    ?: error("conversation not found")
                val conversation = getConversationById(id)?.takeIf { it.id == id && isAllowed(it) }
                    ?: error("conversation not found")
                val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: READ_DEFAULT_LIMIT).coerceIn(1, READ_MAX_LIMIT)
                val messages = conversation.dialogueMessages()
                val visible = messages.withIndex().drop(offset).take(limit).filter {
                    !it.value.isSynthetic && !it.value.isContextCheckpoint && it.value.memoryVisibleText().isNotBlank()
                }
                val shown = visible.map { it.value }
                val payload = buildJsonObject {
                    put("conversation_id", conversation.id.toString())
                    put("title", conversation.title.ifBlank { "Untitled" })
                    put("total", messages.size)
                    put("messages", buildJsonArray {
                        visible.forEach { (position, message) ->
                            add(buildJsonObject {
                                put("position", position)
                                put("date", message.createdAt.date.toString())
                                put("text", message.memoryVisibleText())
                                putSource(conversation, message)
                            })
                        }
                    })
                }
                if (shown.isNotEmpty()) onRead(conversation, shown)
                listOf(UIMessagePart.Text(payload.toString()))
            },
        ),
    )
}

private fun UIMessage.memoryVisibleText(): String = parts.filterIsInstance<UIMessagePart.Text>()
    .joinToString("\n") { it.text }.take(READ_MESSAGE_LENGTH)

/** 当前分支上用户和助手的消息，位置编号以这个列表为准 */
internal fun Conversation.dialogueMessages(): List<UIMessage> =
    currentMessages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
