package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.ui.ToolApprovalState

object AssistantConfigToolUI : ToolUIRenderer {
    override val toolName: String = "update_assistant_config"

    private const val GLOBAL_SCOPE = "共享全局，影响全部参与角色"
    private val labels = linkedMapOf(
        "model_id" to "模型",
        "temperature" to "温度",
        "top_p" to "Top P",
        "system_prompt" to "系统提示词",
        "enable_memory" to "记忆开关",
        "wakeup.participating" to "当前角色参与唤醒",
        "wakeup.global_enabled" to "全局唤醒开关",
        "wakeup.interval_minutes" to "全局唤醒间隔（分钟）",
        "wakeup.start_hour" to "全局唤醒开始小时",
        "wakeup.start_minute" to "全局唤醒开始分钟",
        "wakeup.end_hour" to "全局唤醒结束小时",
        "wakeup.end_minute" to "全局唤醒结束分钟",
        "wakeup.decision_prompt" to "全局唤醒决策提示词",
    )
    private val statuses = setOf("saved", "unchanged", "partial", "error", "partial_or_unpersisted")

    @Composable
    override fun title(context: ToolUIContext): String = display(context).title.preview(80)

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val display = remember(context) { display(context) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            display.lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            display.warnings.forEach { warning ->
                Text(
                    text = "注意：$warning",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("角色配置修改", style = MaterialTheme.typography.titleMedium)
            Summary(context)
        }
    }

    internal data class Display(
        val title: String,
        val lines: List<String>,
        val warnings: List<String> = emptyList(),
    )

    internal fun display(context: ToolUIContext): Display {
        if (context.tool.approvalState is ToolApprovalState.Denied) {
            return Display("配置修改已拒绝", listOf("未批准此次配置修改，未确认任何修改。"))
        }
        if (!context.tool.isExecuted) return requestDisplay(context)
        val result = context.content as? JsonObject
        // 执行层的错误只显示安全说明，不回显可能含凭据的异常原文。
        if (result == null || "error" in result || "tool.error" in result) {
            return Display("配置修改失败或结果不可用", listOf("未获得有效配置结果，未确认保存成功。"))
        }
        val status = result["status"].stringOrNull()?.takeIf { it in statuses }
            ?: return Display("配置修改结果未确认", listOf("结果缺少有效状态，未确认保存成功。"))
        val changes = (result["changes"] as? JsonArray).orEmpty()
        val changeLines = changes.mapNotNull { entry ->
            val change = entry as? JsonObject ?: return@mapNotNull null
            val field = change["field"].stringOrNull() ?: return@mapNotNull null
            val label = labels[field] ?: return@mapNotNull null
            val value = shownValue(field, change["value"])
            val prefix = when (status) {
                "saved", "partial" -> "已修改"
                "unchanged" -> "未变化："
                else -> "待确认修改"
            }
            "$prefix${label}为$value"
        }
        // 字段摘要由白名单和值生成，避免后端摘要绕过开关翻译和提示词截断。
        val summary = if (changes.isEmpty()) {
            result["summary"].stringOrNull()?.takeIf { it.isNotBlank() }?.preview(200)
        } else {
            changeLines.joinToString("；").takeIf { it.isNotBlank() }
        }
        val state = when (status) {
            "saved" -> "配置已保存"
            "unchanged" -> "配置未变化"
            "partial" -> "配置已保存，但同步未完整完成"
            "partial_or_unpersisted" -> "配置可能已修改，保存未确认"
            else -> "配置修改失败，未确认保存成功"
        }
        val title = when (status) {
            "saved", "unchanged" -> summary ?: state
            else -> "$state：${summary ?: "请查看详情"}"
        }
        val warnings = (result["warnings"] as? JsonArray).orEmpty()
            .mapNotNull { it.stringOrNull() }.toMutableList()
        val lines = buildList {
            add(state)
            if (changes.isEmpty() && summary != null) add(summary)
            addAll(changeLines)
            add(
                when (status) {
                    "saved", "partial" -> "已保存的配置从下一轮生效，本轮不热更新。"
                    "unchanged" -> "配置未变化；配置修改从下一轮生效，本轮不热更新。"
                    else -> "仅成功保存的配置从下一轮生效；当前保存或生效情况尚未确认。"
                }
            )
        }
        val hasGlobalChanges = changes.any { entry ->
            isGlobal((entry as? JsonObject)?.get("field").stringOrNull())
        }
        if (hasGlobalChanges) warnings.add(0, "唤醒开关、间隔、时间和决策提示词$GLOBAL_SCOPE。")
        return Display(title, lines, warnings.distinct())
    }

    private fun requestDisplay(context: ToolUIContext): Display {
        val arguments = context.arguments as? JsonObject
        val wakeup = arguments?.get("wakeup") as? JsonObject
        // 只从固定白名单取值，绝不序列化入参或显示未知字段名称/值。
        val fields = labels.keys.mapNotNull { field ->
            val value = if (field.startsWith("wakeup.")) {
                wakeup?.get(field.removePrefix("wakeup."))
            } else {
                arguments?.get(field)
            }
            value?.let { field to it }
        }
        val hasGlobalFields = fields.any { isGlobal(it.first) }
        val phase = if (context.tool.isPending) "申请修改配置" else "配置修改尚未执行"
        val scope = if (hasGlobalFields) "（$GLOBAL_SCOPE）" else "（仅当前角色）"
        val title = "$phase$scope：${fields.joinToString("、") { labels.getValue(it.first) }}"
        val lines = buildList {
            add("$phase$scope，尚未修改任何配置。")
            fields.forEach { (field, value) ->
                add("申请修改${labels.getValue(field)}为${shownValue(field, value)}")
            }
            if (fields.isEmpty()) add("暂无可展示的白名单配置字段。")
            add("审批通过并保存后，从下一轮生效，本轮不热更新。")
        }
        val warnings = buildList {
            if (hasGlobalFields) add("审批范围：唤醒开关、间隔、时间和决策提示词$GLOBAL_SCOPE，并非仅修改当前角色。")
            if (wakeup?.containsKey("participating") == true) {
                add("参与唤醒仅控制当前角色加入或退出，不会自动开启全局唤醒。")
            }
            if (arguments == null || arguments.keys.any { it != "wakeup" && (it.startsWith("wakeup.") || it !in labels) } ||
                (arguments.containsKey("wakeup") && wakeup == null) ||
                wakeup?.keys?.any { "wakeup.$it" !in labels } == true
            ) {
                add("包含未知字段或无效参数，相关内容已隐藏；工具会校验并可能拒绝整个请求。")
            }
        }
        return Display(title, lines, warnings)
    }

    private fun isGlobal(field: String?): Boolean =
        field != null && field in labels && field.startsWith("wakeup.") && field != "wakeup.participating"

    private fun shownValue(field: String, value: JsonElement?): String {
        if (value == JsonNull && field in setOf("temperature", "top_p")) return "默认值（null）"
        val primitive = value as? JsonPrimitive ?: return "（值格式无效，已隐藏）"
        return when (field) {
            "model_id", "system_prompt", "wakeup.decision_prompt" -> {
                val text = primitive.stringOrNull() ?: return "（值格式无效，已隐藏）"
                text.preview(if (field == "model_id") 256 else 160).ifEmpty { "（空）" }
            }
            "enable_memory", "wakeup.participating", "wakeup.global_enabled" -> {
                when (primitive.takeUnless { it.isString }?.booleanOrNull) {
                    true -> "开启"
                    false -> "关闭"
                    null -> "（值格式无效，已隐藏）"
                }
            }
            "temperature", "top_p" -> {
                val number = primitive.takeUnless { it.isString }?.doubleOrNull
                if (number != null && number.isFinite()) primitive.content else "（值格式无效，已隐藏）"
            }
            else -> {
                if (!primitive.isString && primitive.intOrNull != null) primitive.content else "（值格式无效，已隐藏）"
            }
        }
    }

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun String.preview(limit: Int): String = if (length <= limit) this else take(limit - 1) + "…"
}
