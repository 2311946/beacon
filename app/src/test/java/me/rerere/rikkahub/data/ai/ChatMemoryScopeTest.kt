package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.RenderedPrefixStore
import me.rerere.rikkahub.data.ai.transformers.appendUserContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryScopeTest {
    private val assistant = Assistant(enableMemory = true, useGlobalMemory = true)
    private val profile = MemoryFile("/profile.md", "旧记忆", 100L)
    private val preferences = MemoryFile("/preferences.md", "用户偏好", 200L)

    private fun conversation(config: ConversationConfig? = null) = Conversation(
        assistantId = assistant.id,
        messageNodes = emptyList(),
        config = config,
    )

    @Test
    fun `群临时会话清空 config 后仍按持久群关联使用发言助手个人 id`() {
        val owner = Assistant()
        val original = conversation(ConversationConfig(isGroupChat = true)).copy(assistantId = owner.id)
        val settings = Settings(
            assistants = listOf(owner, assistant),
            groupChats = listOf(GroupChat(conversationId = original.id, memberIds = listOf(assistant.id))),
        )
        val temporary = original.copy(assistantId = assistant.id, config = null)

        assertEquals(assistant.id.toString(), settings.chatMemoryId(assistant, temporary))
        assertNotEquals(MemoryRepository.GLOBAL_MEMORY_ID, settings.chatMemoryId(assistant, temporary))
    }

    @Test
    fun `明确群标记即使缺少群关联也不读取全局库`() {
        assertEquals(
            assistant.id.toString(),
            Settings().chatMemoryId(assistant, conversation(ConversationConfig(isGroupChat = true))),
        )
    }

    @Test
    fun `持久群关联优先于未设置群标记的 config`() {
        val conversation = conversation(ConversationConfig(isGroupChat = false))
        val settings = Settings(groupChats = listOf(GroupChat(conversationId = conversation.id)))

        assertEquals(assistant.id.toString(), settings.chatMemoryId(assistant, conversation))
    }

    @Test
    fun `单聊有无 config 均保留 global 选择且无关群不改变作用域`() {
        val settings = Settings(groupChats = listOf(GroupChat(conversationId = conversation().id)))
        listOf(null, ConversationConfig()).forEach { config ->
            assertEquals(
                MemoryRepository.GLOBAL_MEMORY_ID,
                settings.chatMemoryId(assistant, conversation(config)),
            )
        }
    }

    @Test
    fun `单聊未启用 global 时使用助手个人 id`() {
        assertEquals(
            assistant.id.toString(),
            Settings().chatMemoryId(assistant.copy(useGlobalMemory = false), conversation()),
        )
    }

    @Test
    fun `ACTIVE 文件指纹不受输入列表顺序影响`() {
        assertEquals(
            memoryRequestFingerprint(assistant.id.toString(), listOf(profile, preferences)),
            memoryRequestFingerprint(assistant.id.toString(), listOf(preferences, profile)),
        )
    }

    @Test
    fun `正文变化即使路径与更新时间相同也改变指纹`() {
        assertNotEquals(
            memoryRequestFingerprint(assistant.id.toString(), listOf(profile)),
            memoryRequestFingerprint(assistant.id.toString(), listOf(profile.copy(content = "新记忆"))),
        )
    }

    @Test
    fun `文件被删除或移出 ACTIVE 集合时指纹改变`() {
        val namespace = assistant.id.toString()

        assertNotEquals(
            memoryRequestFingerprint(namespace, listOf(profile, preferences)),
            memoryRequestFingerprint(namespace, listOf(profile)),
        )
        assertNotEquals(
            memoryRequestFingerprint(namespace, listOf(profile)),
            memoryRequestFingerprint(namespace, emptyList()),
        )
    }

    @Test
    fun `namespace 改变即使 ACTIVE 文件相同或为空也改变指纹`() {
        listOf(emptyList(), listOf(profile)).forEach { files ->
            assertNotEquals(
                memoryRequestFingerprint(assistant.id.toString(), files),
                memoryRequestFingerprint(MemoryRepository.GLOBAL_MEMORY_ID, files),
            )
        }
    }

    @Test
    fun `文件路径与更新时间变化都会改变指纹`() {
        val before = memoryRequestFingerprint(assistant.id.toString(), listOf(profile))

        assertNotEquals(
            before,
            memoryRequestFingerprint(assistant.id.toString(), listOf(profile.copy(path = "/topics/profile.md"))),
        )
        assertNotEquals(
            before,
            memoryRequestFingerprint(assistant.id.toString(), listOf(profile.copy(updatedAt = 101L))),
        )
    }

    @Test
    fun `真实前缀缓存复用相同 ACTIVE 集合但内容删除与库切换使旧 request_context 失效`() {
        val store = RenderedPrefixStore()
        val user = UIMessage.user("同一条历史消息")
        val source = listOf(user)
        val namespace = assistant.id.toString()
        fun stabilize(namespace: String, files: List<MemoryFile>, context: String): List<UIMessage> {
            val key = listOf("固定配置", namespace, memoryRequestFingerprint(namespace, files)).joinToString("\u0000")
            return store.stabilize("同一会话", key, source, appendUserContext(source, context))
        }

        val original = stabilize(namespace, listOf(profile, preferences), "旧记忆和用户偏好")
        val reordered = stabilize(namespace, listOf(preferences, profile), "不应替换的渲染快照")
        assertEquals(original, reordered)
        assertTrue(original.single().toText().contains("<request_context>"))

        val changedProfile = profile.copy(content = "新记忆")
        val changed = stabilize(namespace, listOf(changedProfile, preferences), "新记忆和用户偏好")
        assertTrue(changed.single().toText().contains("新记忆"))
        assertFalse(changed.single().toText().contains("旧记忆"))

        val removed = stabilize(namespace, listOf(preferences), "仅剩用户偏好")
        assertTrue(removed.single().toText().contains("仅剩用户偏好"))
        assertFalse(removed.single().toText().contains("新记忆"))

        val switched = stabilize(MemoryRepository.GLOBAL_MEMORY_ID, listOf(preferences), "全局库上下文")
        assertTrue(switched.single().toText().contains("全局库上下文"))
        assertFalse(switched.single().toText().contains("仅剩用户偏好"))

        val empty = stabilize(MemoryRepository.GLOBAL_MEMORY_ID, emptyList(), "")
        assertEquals(source, empty)
        assertFalse(empty.single().toText().contains("<request_context>"))
    }
}
