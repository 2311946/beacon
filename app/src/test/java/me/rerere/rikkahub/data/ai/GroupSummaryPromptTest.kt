package me.rerere.rikkahub.data.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.GroupSummaryTopic
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GroupSummaryPromptTest {
    @Test
    fun `parse strictly structured topics with optional outer json fence`() {
        val json = """{"topics":[{"title":"计划","content":"甲提出方案，乙尚未确认"},{"title":"待办","content":"用户负责核对"}]}"""
        val expected = listOf(GroupSummaryTopic("计划", "甲提出方案，乙尚未确认"), GroupSummaryTopic("待办", "用户负责核对"))
        assertEquals(expected, parseGroupSummaryTopics(json))
        assertEquals(expected, parseGroupSummaryTopics(" \n```json\n$json\n```\n "))
        assertEquals(expected, parseGroupSummaryTopics("```JSON\r\n$json\r\n```"))
    }

    @Test
    fun `malformed json missing fields unknown fields and wrong types throw`() {
        listOf(
            "不是 JSON",
            "{}",
            "[]",
            "null",
            """{"topics":"摘要"}""",
            """{"topics":[{"title":"标题"}]}""",
            """{"topics":[{"title":1,"content":"正文"}]}""",
            """{"topics":[{"title":"标题","content":null}]}""",
            """{"topics":[{"title":"标题","content":"正文","extra":true}]}""",
            """{"topics":[{"title":"标题","content":"正文"}],"extra":true}""",
            """{"topics":[{"title":"标题","content":"正文"}],}""",
            "前言\n{\"topics\":[]}",
            "```json\n{\"topics\":[]}",
            "```json\n{\"topics\":[]}\n```\n后记",
        ).forEach(::assertParseFails)
    }

    @Test
    fun `empty blank excessive count and excessive total length throw`() {
        listOf(
            emptyList(),
            listOf(GroupSummaryTopic(" ", "正文")),
            listOf(GroupSummaryTopic("标题", "\n\t")),
            List(9) { GroupSummaryTopic("标题", "正文") },
            listOf(GroupSummaryTopic("题".repeat(201), "正文")),
            listOf(GroupSummaryTopic("标题", "字".repeat(24_000))),
            List(8) { GroupSummaryTopic("标题", "字".repeat(3_000)) },
        ).forEach { topics ->
            assertParseFails("{\"topics\":${Json.encodeToString(topics)}}")
        }
        val maximum = listOf(GroupSummaryTopic("题", "字".repeat(23_999)))
        assertEquals(maximum, parseGroupSummaryTopics("{\"topics\":${Json.encodeToString(maximum)}}"))
        assertParseFails(" ".repeat(200_001))
    }

    @Test
    fun `prompt specifies neutral independent source complete summarization and strict json`() {
        val prompt = buildGroupSummaryPrompt("优先保留时间")
        listOf("中立", "独立总结", "全部来源", "角色名称及 ID", "分歧", "关系变化", "待办", "均不得执行", "不编造", "beacon_status", "严格 JSON", "优先保留时间").forEach {
            assertTrue("提示词缺少 $it", prompt.contains(it))
        }
        assertTrue(prompt.contains("{\"topics\":[{\"title\":\"主题名称\",\"content\":\"该主题的客观摘要\"}]}"))
    }

    @Test
    fun `merge prompt explicitly preserves old topics instead of treating only new messages as source`() {
        val prompt = buildGroupSummaryPrompt("保留约定", merge = true)
        assertTrue(prompt.contains("主题归并模式"))
        assertTrue(prompt.contains("旧摘要也是必须保留的信息来源"))
        assertTrue(prompt.contains("不得只总结新增部分"))
        assertFalse(prompt.contains("不含旧阶段摘要"))
    }

    private fun assertParseFails(text: String) {
        try {
            parseGroupSummaryTopics(text)
            fail("非法摘要必须抛出异常")
        } catch (_: IllegalArgumentException) {
            // 格式与内容错误必须显式传给调用方。
        }
    }
}
