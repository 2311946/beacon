package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationExportContentTest {
    private fun request(
        messages: List<UIMessage>,
        includeReasoning: Boolean = true,
        senderNames: Map<Uuid, String> = emptyMap(),
    ) = ChatExportSnapshot(
        title = "选中的原始聊天", messages = messages, senderNames = senderNames,
        options = ExportOptions(includeReasoning = includeReasoning), exportedAt = "2026-11-14",
    )

    @Test
    fun sameModelMessagesKeepDistinctSenderNames() {
        val modelId = Uuid.random()
        val first = UIMessage.assistant("甲的回复").copy(modelId = modelId)
        val second = UIMessage.assistant("乙的回复").copy(modelId = modelId)
        val names = mapOf(first.id to "角色甲", second.id to "角色乙")
        val markdown = buildMarkdownExport(request(listOf(first, second), senderNames = names)) { it.url }
        assertEquals("角色甲", exportSenderName(first, names))
        assertEquals("角色乙", exportSenderName(second, names))
        assertTrue(markdown.indexOf("**角色甲**:") < markdown.indexOf("甲的回复"))
        assertTrue(markdown.indexOf("**角色乙**:") < markdown.indexOf("乙的回复"))
    }

    @Test
    fun reasoningSwitchCoversNestedToolOutputs() {
        val nestedTool = UIMessagePart.Tool(
            toolCallId = "nested", toolName = "nested_tool", input = "{}",
            output = listOf(UIMessagePart.Reasoning("内层思考"), UIMessagePart.Text("工具正文")),
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("顶层思考"),
                UIMessagePart.Tool(
                    toolCallId = "outer", toolName = "outer_tool", input = "{}",
                    output = listOf(UIMessagePart.Reasoning("外层思考"), nestedTool),
                ),
                UIMessagePart.Text("最终正文"),
            ),
        )
        // 构建器自身也检查开关，不依赖调用者已过滤。
        val hidden = buildMarkdownExport(request(listOf(message), includeReasoning = false)) { it.url }
        assertFalse(hidden.contains("思考"))
        assertTrue(hidden.contains("工具正文"))
        assertTrue(hidden.contains("最终正文"))
        val shown = buildMarkdownExport(request(listOf(message))) { it.url }
        assertTrue(shown.contains("顶层思考"))
        assertTrue(shown.contains("外层思考"))
        assertTrue(shown.contains("内层思考"))
    }

    @Test
    fun snapshotCopiesMessageAndNestedOutputLists() {
        val output = mutableListOf<UIMessagePart>(UIMessagePart.Text("原工具输出"))
        val parts = mutableListOf<UIMessagePart>(
            UIMessagePart.Text("原消息"),
            UIMessagePart.Tool("call", "tool", "{}", output),
        )
        val selected = mutableListOf(UIMessage(role = MessageRole.ASSISTANT, parts = parts))
        val snapshot = snapshotExportMessages(selected, includeReasoning = false)
        selected.clear()
        parts.clear()
        output.clear()
        assertEquals(1, snapshot.size)
        assertEquals("原消息", (snapshot.single().parts[0] as UIMessagePart.Text).text)
        assertEquals("原工具输出", ((snapshot.single().parts[1] as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun snapshotRecursivelyRemovesReasoningWithoutChangingOriginal() {
        val nested = UIMessagePart.Tool("nested", "tool", "{}", listOf(UIMessagePart.Reasoning("隐藏")))
        val message = UIMessage.assistant("正文").copy(parts = listOf(nested))
        val result = snapshotExportMessages(listOf(message), includeReasoning = false)
        assertTrue((result.single().parts.single() as UIMessagePart.Tool).output.isEmpty())
        assertEquals(1, nested.output.size)
    }

    @Test
    fun attachmentLinksAndSelectedMessageOrderArePreserved() {
        val first = UIMessage.user("第一个版本").copy(parts = listOf(
            UIMessagePart.Text("第一个版本"),
            UIMessagePart.Image("data:image/png;base64,abc"),
            UIMessagePart.Document("file:///attachment.pdf", "资料.pdf"),
            UIMessagePart.Audio("file:///audio.mp3"),
            UIMessagePart.Video("file:///video.mp4"),
        ))
        val second = UIMessage.assistant("第二条")
        val markdown = buildMarkdownExport(request(listOf(first, second))) { it.url }
        assertTrue(markdown.contains("![图片](data:image/png;base64,abc)"))
        assertTrue(markdown.contains("[附件：资料.pdf](file:///attachment.pdf)"))
        assertTrue(markdown.contains("[音频](file:///audio.mp3)"))
        assertTrue(markdown.contains("[视频](file:///video.mp4)"))
        assertTrue(markdown.indexOf("第一个版本") < markdown.indexOf("第二条"))
    }

    @Test
    fun internalTaskPromptIsProjectedBeforeMarkdownExport() {
        val internalMessage = UIMessage.user("").copy(parts = listOf(
            UIMessagePart.Text("不应泄露的内部任务提示", metadata = buildJsonObject {
                put("beacon_event", JsonPrimitive("autonomous_task"))
                put("task_status", JsonPrimitive("completed"))
            }),
        ))
        val projected = snapshotExportMessages(listOf(internalMessage).map { it.forChatRecord() }, true)
        val markdown = buildMarkdownExport(request(projected)) { it.url }
        assertFalse(markdown.contains("不应泄露的内部任务提示"))
        assertFalse(markdown.contains("beacon_event"))
        assertTrue(markdown.contains("自主任务：已完成"))
    }

    @Test
    fun toolTextWithCodeFenceDoesNotBreakExportFence() {
        val message = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("id", "tool", "{}", listOf(UIMessagePart.Text("```text\n输出\n```"))),
        ))
        val markdown = buildMarkdownExport(request(listOf(message))) { it.url }
        assertTrue(markdown.contains("````text\n```text\n输出\n```\n````"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun imageAtHeightLimitFailsInsteadOfSilentlyTruncating() {
        checkExportImageSize(width = 540, height = 10000, maxHeight = 10000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedImageFailsBeforeBitmapAllocation() {
        checkExportImageSize(width = 3000, height = 9000, maxHeight = 10000)
    }

    @Test
    fun imageBelowLimitsIsAccepted() {
        checkExportImageSize(width = 1080, height = 9999, maxHeight = 20000)
    }
}
