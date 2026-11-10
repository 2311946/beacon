package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class StableUserContextTest {
    @Test fun environmentValuesAreDeterministicAndOnlyAddedToUser() {
        val system = UIMessage.system("固定环境引用")
        val user = UIMessage.user("问题")
        val messages = listOf(system, user)
        val first = appendEnvironmentContext(messages, linkedMapOf("timezone" to "Asia/Shanghai", "locale" to "zh-CN"))
        val second = appendEnvironmentContext(messages, linkedMapOf("locale" to "zh-CN", "timezone" to "Asia/Shanghai"))
        assertEquals(first, second)
        assertEquals(system, first.first())
        assertTrue(first.last().toText().contains("timezone: Asia/Shanghai"))
        assertTrue(first.last().toText().contains("locale: zh-CN"))
        assertEquals(messages, appendEnvironmentContext(messages, emptyMap()))
        assertEquals(listOf(system), appendEnvironmentContext(listOf(system), mapOf("locale" to "zh-CN")))
    }

    @Test fun dynamicContextDoesNotChangeSystemOrSplitTools() {
        val system = UIMessage.system("固定系统")
        val user = UIMessage.user("问题")
        val assistant = UIMessage.assistant("工具结果")
        val result = appendUserContext(listOf(system, user, assistant), "变化的记忆")
        assertEquals(system, result[0])
        assertEquals(assistant, result[2])
        assertTrue(result[1].toText().contains("变化的记忆"))
        assertEquals(user.id, result[1].id)
    }

    @Test fun datesComeFromHistoryNotRequestClock() {
        val user = UIMessage.user("你好").copy(createdAt = LocalDateTime.parse("2026-11-07T12:00:00"))
        assertTrue(appendMessageDates(listOf(user)).single().toText().contains("2026-11-07"))
        assertEquals(appendMessageDates(listOf(user)), appendMessageDates(listOf(user)))
    }

    @Test fun appendReusesOldRenderedPrefixButNewTurnGetsNewContext() {
        val store = RenderedPrefixStore()
        val system = UIMessage.system("固定")
        val first = UIMessage.user("一")
        val initial = store.stabilize("c", "config", listOf(first), appendUserContext(listOf(system, first), "记忆1"))
        val second = UIMessage.user("二")
        val next = store.stabilize("c", "config", listOf(first, second), appendUserContext(listOf(system, first, second), "记忆2"))
        assertEquals(initial, next.take(initial.size))
        assertTrue(next.last().toText().contains("记忆2"))
    }

    @Test fun editedHistoryAndConfigurationAreNotFrozen() {
        val store = RenderedPrefixStore()
        val first = UIMessage.user("一")
        store.stabilize("c", "config", listOf(first), appendUserContext(listOf(first), "记忆1"))
        val edited = first.copy(parts = UIMessage.user("编辑").parts)
        val changed = store.stabilize("c", "config", listOf(edited), listOf(edited))
        assertEquals("编辑", changed.single().toText())
        val newConfig = store.stabilize("c", "config2", listOf(edited), appendUserContext(listOf(edited), "新配置"))
        assertTrue(newConfig.single().toText().contains("新配置"))
    }

    @Test fun sameHistoryReusesEnvironmentSnapshot() {
        val store = RenderedPrefixStore()
        val user = UIMessage.user("问题")
        val before = store.stabilize("c", "k", listOf(user), appendUserContext(listOf(user), "环境A"))
        val after = store.stabilize("c", "k", listOf(user), appendUserContext(listOf(user), "环境B"))
        assertEquals(before, after)
    }
}
