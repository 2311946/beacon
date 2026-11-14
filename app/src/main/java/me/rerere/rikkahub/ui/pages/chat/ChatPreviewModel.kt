package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

internal data class ChatPreviewEntry(
    val index: Int,
    val nodeId: Uuid,
    val message: UIMessage,
    val senderName: String,
    val text: String,
)

internal data class ChatPreviewRound(
    val id: Uuid,
    val firstIndex: Int,
    val lastIndex: Int,
    val entries: List<ChatPreviewEntry>,
    val favorite: Boolean,
) {
    fun matches(query: String): Boolean = entries.any {
        it.text.contains(query, ignoreCase = true) || it.senderName.contains(query, ignoreCase = true)
    }
}

/** 只折叠真实消息节点；群总结保存在 config，不在这里伪造成消息。 */
internal fun List<MessageNode>.toChatPreviewRounds(senderNames: Map<Uuid, String>): List<ChatPreviewRound> {
    val groups = mutableListOf<MutableList<IndexedValue<MessageNode>>>()
    forEachIndexed { index, node ->
        // 原始 USER 角色仍是回合边界，但其展示身份由安全投影决定。
        if (node.currentMessage.role == MessageRole.USER || groups.isEmpty()) groups.add(mutableListOf())
        groups.last().add(IndexedValue(index, node))
    }
    return groups.map { group ->
        ChatPreviewRound(
            id = group.first().value.id,
            firstIndex = group.first().index,
            lastIndex = group.last().index,
            entries = group.map { (index, node) ->
                val message = node.currentMessage.forChatRecord()
                ChatPreviewEntry(index, node.id, message, senderNames[message.id].orEmpty(), message.toChatPreviewText())
            },
            favorite = group.any { it.value.isFavorite },
        )
    }
}

/** 后台地图可能晚于删除/分支切换一帧，绝不沿用旧位置跳到另一条消息。 */
internal fun List<MessageNode>.chatPreviewIndexOf(nodeId: Uuid): Int = indexOfFirst { it.id == nodeId }

private val CodeFenceRegex = Regex("```[\\w-]*")
private val HeadingRegex = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
private val QuoteRegex = Regex("(?m)^\\s{0,3}>\\s?")
private val EmphasisRegex = Regex("\\*\\*|__|~~")
private val WhitespaceRegex = Regex("\\s+")

private fun UIMessage.toChatPreviewText(): String = toText()
    .replace(CodeFenceRegex, " ")
    .replace(HeadingRegex, "")
    .replace(QuoteRegex, "")
    .replace(EmphasisRegex, "")
    .replace(WhitespaceRegex, " ")
    .trim()

/** 搜索使用全文，只有进入 Text 排版的字符串才截短。 */
internal fun chatPreviewSnippet(text: String, query: String): String {
    val matchIndex = if (query.isBlank()) -1 else text.indexOf(query, ignoreCase = true)
    val start = if (matchIndex <= 24) 0 else {
        text.indexOf(' ', matchIndex - 24).let { if (it in 0 until matchIndex) it + 1 else matchIndex - 24 }
    }
    return (if (start == 0) text else "…" + text.substring(start)).take(300)
}
