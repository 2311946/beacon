package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

data class ExportOptions(
    val includeReasoning: Boolean = true,
    val expandReasoning: Boolean = false,
)

internal data class ChatExportSnapshot(
    val title: String,
    val messages: List<UIMessage>,
    // 按消息 ID 而非模型 ID 查找，允许同模型的多个群成员使用不同名称。
    val senderNames: Map<Uuid, String>,
    val options: ExportOptions,
    val exportedAt: String,
)

internal fun snapshotExportMessages(messages: List<UIMessage>, includeReasoning: Boolean): List<UIMessage> =
    messages.map { it.copy(parts = snapshotExportParts(it.parts, includeReasoning)) }

internal fun snapshotExportParts(parts: List<UIMessagePart>, includeReasoning: Boolean): List<UIMessagePart> =
    parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Reasoning -> if (includeReasoning) part.copy() else null
            is UIMessagePart.Tool -> part.copy(output = snapshotExportParts(part.output, includeReasoning))
            is UIMessagePart.Text -> part.copy()
            is UIMessagePart.Image -> part.copy()
            is UIMessagePart.Document -> part.copy()
            is UIMessagePart.Video -> part.copy()
            is UIMessagePart.Audio -> part.copy()
            else -> part
        }
    }

internal fun exportSenderName(message: UIMessage, senderNames: Map<Uuid, String>): String =
    senderNames[message.id]?.takeIf { it.isNotBlank() } ?: when (message.role) {
        MessageRole.USER -> "用户"
        MessageRole.ASSISTANT -> message.modelSnapshot?.displayName?.takeIf { it.isNotBlank() }
            ?: message.modelSnapshot?.modelId?.takeIf { it.isNotBlank() } ?: "助手"
        MessageRole.SYSTEM -> "系统"
        MessageRole.TOOL -> "工具"
    }

internal const val EXPORT_MAX_IMAGE_HEIGHT_DP = 10000
internal const val EXPORT_MAX_IMAGE_PIXELS = 24000000L

internal fun checkExportImageSize(width: Int, height: Int, maxHeight: Int) {
    require(width > 0 && height > 0) { "导出图片尺寸无效" }
    require(height < maxHeight) { "聊天长图达到高度上限（10000dp），请减少选中消息或使用 Markdown 导出" }
    require(width.toLong() * height <= EXPORT_MAX_IMAGE_PIXELS) {
        "聊天长图超过像素上限，请减少选中消息或使用 Markdown 导出"
    }
}

internal fun buildMarkdownExport(
    snapshot: ChatExportSnapshot,
    imageSource: (UIMessagePart.Image) -> String,
): String = buildString {
    appendLine("# ${snapshot.title.replace('\n', ' ').replace('\r', ' ')}")
    appendLine()
    appendLine("*导出于 ${snapshot.exportedAt}*")
    appendLine()
    snapshot.messages.forEach { message ->
        val sender = exportSenderName(message, snapshot.senderNames)
        appendLine("**${escapeExportLabel(sender)}**:")
        appendLine()
        appendExportParts(message.parts, snapshot.options.includeReasoning, imageSource)
        appendLine()
        appendLine("---")
    }
}

private fun escapeExportLabel(value: String): String = value
    .replace("\\", "\\\\")
    .replace("*", "\\*")
    .replace("[", "\\[")
    .replace("]", "\\]")
    .replace('\n', ' ')
    .replace('\r', ' ')

private fun StringBuilder.appendExportCode(text: String, language: String) {
    val longestFence = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestFence + 1))
    appendLine("$fence$language")
    appendLine(text)
    appendLine(fence)
}

private fun StringBuilder.appendExportParts(
    parts: List<UIMessagePart>,
    includeReasoning: Boolean,
    imageSource: (UIMessagePart.Image) -> String,
    toolOutput: Boolean = false,
) {
    parts.forEach { part ->
        when (part) {
            is UIMessagePart.Text -> {
                if (toolOutput) appendExportCode(part.text, "text") else appendLine(part.text)
            }
            is UIMessagePart.Reasoning -> if (includeReasoning) {
                part.reasoning.lines().forEach { appendLine("> $it") }
                appendLine()
            }
            is UIMessagePart.Image -> appendLine("![图片](${imageSource(part)})")
            is UIMessagePart.Document -> appendLine("[附件：${escapeExportLabel(part.fileName)}](${part.url})")
            is UIMessagePart.Video -> appendLine("[视频](${part.url})")
            is UIMessagePart.Audio -> appendLine("[音频](${part.url})")
            is UIMessagePart.Tool -> {
                appendLine("**工具**：${escapeExportLabel(part.toolName)}")
                if (part.toolCallId.isNotBlank()) appendLine("调用 ID：${escapeExportLabel(part.toolCallId)}")
                appendLine("输入：")
                appendExportCode(part.input, "json")
                if (part.output.isNotEmpty()) {
                    appendLine("输出：")
                    appendExportParts(part.output, includeReasoning, imageSource, toolOutput = true)
                }
                appendLine()
            }
            is UIMessagePart.ServerTool -> {
                appendLine("**服务端工具**：${escapeExportLabel(part.toolName)}（${part.status}）")
                part.input?.let { appendExportCode(it.toString(), "json") }
                part.output?.let { appendExportCode(it.toString(), "json") }
            }
            else -> Unit
        }
    }
}
