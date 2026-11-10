package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

@Serializable
data class ChatImageGenerationConfig(
    val modelId: Uuid? = null,
    val automatic: Boolean = false,
)

@Serializable
enum class ChatImageGenerationStatus {
    SUBMITTING, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    val isActive: Boolean get() = this == SUBMITTING || this == RUNNING
}

@Serializable
data class ChatImageGenerationState(
    val prompt: String,
    val status: ChatImageGenerationStatus,
    val recordId: Uuid? = null,
    val error: String? = null,
)

fun UIMessage.chatImageGenerationState(): ChatImageGenerationState? {
    val metadata = ChatImageGeneration.marker(this)?.metadata ?: return null
    val status = metadata["status"]?.jsonPrimitive?.content?.let {
        runCatching { ChatImageGenerationStatus.valueOf(it) }.getOrNull()
    } ?: if (metadata["error"] != null) ChatImageGenerationStatus.FAILED else ChatImageGenerationStatus.RUNNING
    return ChatImageGenerationState(
        prompt = metadata["prompt"]?.jsonPrimitive?.content ?: ChatImageGeneration.prompt(toText()) ?: toText(),
        status = status,
        recordId = metadata["record_id"]?.jsonPrimitive?.content?.let { runCatching { Uuid.parse(it) }.getOrNull() },
        error = metadata["error"]?.jsonPrimitive?.content,
    )
}

fun UIMessage.withChatImageGenerationState(state: ChatImageGenerationState): UIMessage {
    // 使用独立的空文本标记，不能覆盖正文中主动消息、来源等已有元数据。
    val index = parts.indexOfFirst(ChatImageGeneration::isMarker)
    val cleanParts = parts.map { part ->
        if (part !is UIMessagePart.Text || !ChatImageGeneration.isMarker(part)) part else {
            part.copy(metadata = part.metadata?.let { JsonObject(it - setOf("beacon_event", "prompt", "status", "record_id", "error")) })
        }
    }.toMutableList()
    val text = if (index >= 0) cleanParts[index] as UIMessagePart.Text else UIMessagePart.Text("")
    val updated = text.copy(metadata = buildJsonObject {
        text.metadata?.forEach { (key, value) -> put(key, value) }
        put("beacon_event", ChatImageGeneration.EVENT)
        put("prompt", state.prompt)
        put("status", state.status.name)
        state.recordId?.let { put("record_id", it.toString()) }
        state.error?.let { put("error", it) }
    })
    if (index >= 0) cleanParts[index] = updated else cleanParts.add(updated)
    return copy(parts = cleanParts)
}

fun UIMessage.canManuallyGenerateChatImage(): Boolean =
    toText().isNotBlank() && chatImageGenerationState()?.status.let {
        it == null || it == ChatImageGenerationStatus.FAILED || it == ChatImageGenerationStatus.CANCELLED
    }

fun UIMessage.shouldAutoGenerateChatImage(enabled: Boolean): Boolean =
    enabled && role == MessageRole.ASSISTANT && chatImageGenerationState() == null &&
        ChatImageGeneration.prompt(toText()) != null

object ChatImageGeneration {
    const val EVENT = "chat_image_generation"
    const val INSTRUCTION = "如需实际生成图片，独立一行输出 [生成图片: 详细画面描述]。每次回复最多一个指令；不要在普通讨论、引用或代码示例中输出此指令。只有生成成功才表示图片已完成。"

    // 只接受完整、独立成行的显式命令，不把普通描述或代码示例当作付费指令。
    fun prompt(text: String): String? = me.rerere.rikkahub.data.ai.parseChatImageDirective(text)?.prompt

    fun marker(message: UIMessage): UIMessagePart.Text? = message.parts.filterIsInstance<UIMessagePart.Text>()
        .firstOrNull { (it.metadata?.get("beacon_event") as? JsonPrimitive)?.content == EVENT }

    fun isMarker(part: UIMessagePart): Boolean = part is UIMessagePart.Text &&
        (part.metadata?.get("beacon_event") as? JsonPrimitive)?.content == EVENT

    fun marker(recordId: Uuid? = null, error: String? = null): UIMessagePart.Text = UIMessagePart.Text(
        text = "",
        metadata = buildJsonObject {
            put("beacon_event", EVENT)
            recordId?.let { put("record_id", it.toString()) }
            error?.let { put("error", it) }
        },
    )
}
