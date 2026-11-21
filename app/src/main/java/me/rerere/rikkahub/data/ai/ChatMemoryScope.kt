package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.memoryId
import java.security.MessageDigest

/** 群发言的临时会话可能已清空 config，仍须按持久化群关联使用发言者个人库。 */
internal fun Settings.chatMemoryId(assistant: Assistant, conversation: Conversation): String =
    if (conversation.config?.isGroupChat == true || groupChats.any { it.conversationId == conversation.id }) {
        assistant.id.toString()
    } else {
        assistant.memoryId
    }

/** 输入只含 ACTIVE 文件；成员消失即使正文未变，也会使旧 request_context 失效。 */
internal fun memoryRequestFingerprint(namespace: String, activeFiles: List<MemoryFile>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
        digest.update(bytes)
    }
    add(namespace)
    activeFiles.sortedBy { it.path }.forEach { file ->
        add(file.path)
        add("ACTIVE")
        add(file.version)
        add(file.updatedAt.toString())
        add(file.content)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
