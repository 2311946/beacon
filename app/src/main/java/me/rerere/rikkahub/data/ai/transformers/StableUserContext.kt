package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** 环境信息附在用户消息内，不插入工具调用与结果之间，不改变 system 的优先级。 */
internal fun appendUserContext(messages: List<UIMessage>, content: String): List<UIMessage> {
    if (content.isBlank()) return messages
    val index = messages.indexOfLast { it.role == MessageRole.USER && !it.isSynthetic }
    if (index < 0) return messages
    return messages.mapIndexed { i, message ->
        if (i != index) message else message.copy(
            parts = message.parts + UIMessagePart.Text("\n<request_context>\n$content\n</request_context>"),
        )
    }
}

internal fun appendEnvironmentContext(messages: List<UIMessage>, environment: Map<String, String>): List<UIMessage> {
    if (environment.isEmpty()) return messages
    return appendUserContext(messages, environment.toSortedMap().entries.joinToString("\n") { (key, value) ->
        "$key: $value"
    })
}

/** 进程内有界前缀快照：原历史及显式配置未变时复用已渲染前缀，新增轮次获取新环境。
 * 不持久化聊天副本；进程重启/淘汰后重新构建。编辑历史从首个变化节点开始失效。
 */
internal class RenderedPrefixStore {
    private data class Snapshot(val key: String, val source: List<UIMessage>, val rendered: List<UIMessage>)
    private val snapshots = LinkedHashMap<String, Snapshot>()

    @Synchronized
    fun stabilize(conversation: String, key: String, source: List<UIMessage>, rendered: List<UIMessage>): List<UIMessage> {
        val previous = snapshots.remove(conversation)
        var prefix = 0
        if (previous?.key == key) {
            while (prefix < minOf(previous.source.size, source.size)) {
                val a = previous.source[prefix]
                val b = source[prefix]
                if (a.id != b.id || a.role != b.role || a.parts != b.parts || a.createdAt != b.createdAt ||
                    a.isSynthetic != b.isSynthetic || a.isContextCheckpoint != b.isContextCheckpoint) break
                prefix++
            }
        }
        val result = if (previous != null && prefix > 0) {
            val lastId = source[prefix - 1].id
            val before = previous.rendered.indexOfLast { it.id == lastId }
            val after = rendered.indexOfLast { it.id == lastId }
            if (before >= 0 && after >= 0) {
                rendered.filter { it.role == MessageRole.SYSTEM } +
                    previous.rendered.take(before + 1).filterNot { it.role == MessageRole.SYSTEM } +
                    rendered.drop(after + 1).filterNot { it.role == MessageRole.SYSTEM }
            } else rendered
        } else rendered
        // 最多保留8个会话、每会话512个节点和约1Mi字符，避免无限保存聊天副本。
        if (source.size <= 512 && result.sumOf { it.toText().length } <= 1_048_576) {
            while (snapshots.size >= 8) snapshots.remove(snapshots.keys.first())
            snapshots[conversation] = Snapshot(key, source.toList(), result.toList())
        }
        return result
    }
}

/** system 日期标记在每条真实用户消息补充其自身日期；旧日期不会随请求时间变化。 */
internal fun appendMessageDates(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
    if (message.role != MessageRole.USER || message.isSynthetic) message else message.copy(
        parts = message.parts + UIMessagePart.Text("\n<message_date>${message.createdAt.date}</message_date>"),
    )
}
