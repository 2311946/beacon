package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantConfigToolUITest {
    @Test
    fun `审批前仅展示申请且不使用未经执行的成功结果`() {
        val display = AssistantConfigToolUI.display(
            context(
                arguments = json("""{"temperature":0.7,"enable_memory":true}"""),
                content = result("saved", change("temperature", JsonPrimitive(0.7))),
            )
        )

        assertTrue(display.title.startsWith("申请修改配置（仅当前角色）"))
        assertTrue(display.lines.contains("申请修改温度为0.7"))
        assertTrue(display.lines.contains("申请修改记忆开关为开启"))
        assertTrue(display.lines.any { it.contains("尚未修改任何配置") })
        assertTrue(display.lines.any { it.contains("审批通过并保存后") && it.contains("本轮不热更新") })
        assertFalse(display.text().contains("配置已保存"))
        assertFalse(display.lines.any { it.startsWith("已修改") })
    }

    @Test
    fun `审批通过但未执行仍不宣称保存`() {
        val display = AssistantConfigToolUI.display(
            context(
                arguments = json("""{"enable_memory":false}"""),
                approval = ToolApprovalState.Approved,
            )
        )

        assertTrue(display.title.startsWith("配置修改尚未执行"))
        assertTrue(display.lines.any { it.contains("尚未修改任何配置") })
        assertFalse(display.text().contains("配置已保存"))
    }

    @Test
    fun `拒绝优先于成功结果且不回显理由或参数`() {
        listOf(false, true).forEach { executed ->
            val display = AssistantConfigToolUI.display(
                context(
                    arguments = json("""{"system_prompt":"不应回显的提示词"}"""),
                    content = result("saved", change("enable_memory", JsonPrimitive(true))),
                    approval = ToolApprovalState.Denied("不应回显的拒绝理由"),
                    executed = executed,
                )
            )

            assertEquals("配置修改已拒绝", display.title)
            assertEquals(listOf("未批准此次配置修改，未确认任何修改。"), display.lines)
            assertTrue(display.warnings.isEmpty())
            assertFalse(display.text().contains("不应回显"))
            assertFalse(display.text().contains("配置已保存"))
        }
    }

    @Test
    fun `保存结果展示实际字段而不是原申请或后端字段摘要`() {
        val display = AssistantConfigToolUI.display(
            context(
                arguments = json("""{"temperature":0.1}"""),
                content = result(
                    "saved",
                    change("model_id", JsonPrimitive("model-123")),
                    change("temperature", JsonPrimitive(0.8)),
                    change("top_p", JsonNull),
                    change("system_prompt", JsonPrimitive("新的提示词")),
                    change("enable_memory", JsonPrimitive(true), "已修改记忆开关为true"),
                    summary = "已修改记忆开关为true",
                ),
                executed = true,
            )
        )

        assertEquals("配置已保存", display.lines.first())
        assertTrue(display.lines.contains("已修改模型为model-123"))
        assertTrue(display.lines.contains("已修改温度为0.8"))
        assertTrue(display.lines.contains("已修改Top P为默认值（null）"))
        assertTrue(display.lines.contains("已修改系统提示词为新的提示词"))
        assertTrue(display.lines.contains("已修改记忆开关为开启"))
        assertTrue(display.lines.last().contains("从下一轮生效，本轮不热更新"))
        assertFalse(display.text().contains("为true"))
        assertFalse(display.text().contains("温度为0.1"))
    }

    @Test
    fun `所有布尔开关在申请和保存结果中均展示中文`() {
        listOf("enable_memory", "wakeup.participating", "wakeup.global_enabled").forEach { field ->
            listOf(true, false).forEach { enabled ->
                val value = JsonPrimitive(enabled)
                val arguments = buildJsonObject {
                    if (field.startsWith("wakeup.")) {
                        put("wakeup", buildJsonObject { put(field.removePrefix("wakeup."), value) })
                    } else {
                        put(field, value)
                    }
                }
                val label = when (field) {
                    "enable_memory" -> "记忆开关"
                    "wakeup.participating" -> "当前角色参与唤醒"
                    else -> "全局唤醒开关"
                }
                val shown = if (enabled) "开启" else "关闭"
                val pending = AssistantConfigToolUI.display(context(arguments = arguments))
                val saved = AssistantConfigToolUI.display(
                    context(
                        content = result(
                            "saved",
                            change(field, value, "已修改${label}为$enabled"),
                            summary = "已修改${label}为$enabled",
                        ),
                        executed = true,
                    )
                )

                assertTrue(pending.lines.contains("申请修改${label}为$shown"))
                assertTrue(saved.lines.contains("已修改${label}为$shown"))
                assertFalse(saved.text().contains(enabled.toString()))
            }
        }
    }

    @Test
    fun `持久化未确认时不会宣称修改已成功保存`() {
        val display = AssistantConfigToolUI.display(
            context(
                content = result(
                    "partial_or_unpersisted",
                    change("enable_memory", JsonPrimitive(true), "已修改记忆开关为true"),
                    warnings = listOf("内存可能已改变，但持久化未确认，不能声称已经回滚。"),
                ),
                executed = true,
            )
        )

        assertTrue(display.title.startsWith("配置可能已修改，保存未确认"))
        assertTrue(display.lines.contains("待确认修改记忆开关为开启"))
        assertTrue(display.lines.last().contains("当前保存或生效情况尚未确认"))
        assertTrue(display.warnings.single().contains("不能声称已经回滚"))
        assertFalse(display.text().contains("配置已保存"))
        assertFalse(display.lines.any { it.startsWith("已修改") })
    }

    @Test
    fun `部分同步失败仍明确保存和同步是不同状态`() {
        val display = AssistantConfigToolUI.display(
            context(
                content = result(
                    "partial",
                    change("model_id", JsonPrimitive("model-123")),
                    warnings = listOf("当前会话模型同步失败。"),
                ),
                executed = true,
            )
        )

        assertTrue(display.title.startsWith("配置已保存，但同步未完整完成"))
        assertTrue(display.lines.contains("已修改模型为model-123"))
        assertEquals(listOf("当前会话模型同步失败。"), display.warnings)
    }

    @Test
    fun `未变化和失败结果不展示已修改前缀`() {
        listOf("unchanged" to "未变化：", "error" to "待确认修改").forEach { (status, prefix) ->
            val display = AssistantConfigToolUI.display(
                context(
                    content = result(status, change("temperature", JsonPrimitive(0.7))),
                    executed = true,
                )
            )

            assertTrue(display.lines.contains("${prefix}温度为0.7"))
            assertFalse(display.lines.any { it.startsWith("已修改") })
            assertFalse(display.text().contains("配置已保存"))
        }
    }

    @Test
    fun `未知输入字段名称和值不回显`() {
        val display = AssistantConfigToolUI.display(
            context(
                arguments = json(
                    """{"enable_memory":true,"api_key_secret":"凭据甲","wakeup.unknown_secret":"凭据乙","wakeup":{"participating":false,"unknown_nested_secret":"凭据丙"}}"""
                )
            )
        )

        assertTrue(display.lines.contains("申请修改记忆开关为开启"))
        assertTrue(display.lines.contains("申请修改当前角色参与唤醒为关闭"))
        assertTrue(display.warnings.any { it.contains("未知字段或无效参数") })
        listOf("api_key_secret", "wakeup.unknown_secret", "unknown_nested_secret", "凭据甲", "凭据乙", "凭据丙")
            .forEach { assertFalse(display.text().contains(it)) }
    }

    @Test
    fun `未知结果字段及其摘要不回显`() {
        val display = AssistantConfigToolUI.display(
            context(
                content = result(
                    "saved",
                    change("unknown_secret", JsonPrimitive("秘密值"), "秘密摘要"),
                    summary = "秘密摘要",
                ),
                executed = true,
            )
        )

        listOf("unknown_secret", "秘密值", "秘密摘要").forEach {
            assertFalse(display.text().contains(it))
        }
    }

    @Test
    fun `所有全局唤醒字段的申请与结果均明确共享作用域`() {
        val fields = linkedMapOf(
            "global_enabled" to JsonPrimitive(true),
            "interval_minutes" to JsonPrimitive(30),
            "start_hour" to JsonPrimitive(8),
            "start_minute" to JsonPrimitive(15),
            "end_hour" to JsonPrimitive(22),
            "end_minute" to JsonPrimitive(45),
            "decision_prompt" to JsonPrimitive("判断是否唤醒"),
        )
        fields.forEach { (field, value) ->
            val pending = AssistantConfigToolUI.display(
                context(arguments = buildJsonObject { put("wakeup", buildJsonObject { put(field, value) }) })
            )
            val saved = AssistantConfigToolUI.display(
                context(
                    content = result("saved", change("wakeup.$field", value)),
                    executed = true,
                )
            )

            assertTrue(pending.title.contains("共享全局，影响全部参与角色"))
            assertTrue(pending.warnings.any { it.contains("并非仅修改当前角色") })
            assertTrue(saved.warnings.any { it.contains("共享全局，影响全部参与角色") })
        }
    }

    @Test
    fun `参与唤醒仅影响当前角色且不会自动开启全局唤醒`() {
        val display = AssistantConfigToolUI.display(
            context(arguments = json("""{"wakeup":{"participating":true}}"""))
        )

        assertTrue(display.title.contains("仅当前角色"))
        assertTrue(display.warnings.single().contains("不会自动开启全局唤醒"))
        assertFalse(display.text().contains("共享全局"))
    }

    @Test
    fun `无效执行结果不会被解释为保存成功也不泄露异常`() {
        val invalidResults = listOf(
            null,
            JsonNull,
            JsonPrimitive("秘密异常"),
            json("[]"),
            json("{}"),
            json("""{"status":"unknown","summary":"配置已保存"}"""),
            json("""{"status":true,"summary":"配置已保存"}"""),
            json("""{"status":"saved","error":"秘密异常"}"""),
            json("""{"status":"saved","tool.error":"秘密异常"}"""),
        )
        invalidResults.forEach { content ->
            val display = AssistantConfigToolUI.display(context(content = content, executed = true))

            assertTrue(display.lines.single().contains("未确认保存成功"))
            assertFalse(display.text().contains("配置已保存"))
            assertFalse(display.text().contains("秘密异常"))
        }
    }

    @Test
    fun `长提示词在申请和保存结果中均截断且后端摘要无法绕过`() {
        listOf("system_prompt", "wakeup.decision_prompt").forEach { field ->
            val longPrompt = "文".repeat(300) + "不可见尾部"
            val value = JsonPrimitive(longPrompt)
            val arguments = if (field == "system_prompt") {
                buildJsonObject { put(field, value) }
            } else {
                buildJsonObject { put("wakeup", buildJsonObject { put("decision_prompt", value) }) }
            }
            val pending = AssistantConfigToolUI.display(context(arguments = arguments))
            val saved = AssistantConfigToolUI.display(
                context(
                    content = result("saved", change(field, value, longPrompt), summary = longPrompt),
                    executed = true,
                )
            )

            listOf(pending, saved).forEach { display ->
                val line = display.lines.single { it.endsWith("…") }
                assertEquals("文".repeat(159) + "…", line.substringAfter("为"))
                assertFalse(display.text().contains("不可见尾部"))
                assertFalse(display.text().contains("文".repeat(160)))
            }
        }
    }

    @Test
    fun `无效字段值隐藏而不是序列化或把字符串当成开关`() {
        val display = AssistantConfigToolUI.display(
            context(
                arguments = json(
                    """{"enable_memory":"true","system_prompt":{"secret":"秘密值"},"temperature":"0.7","wakeup":{"interval_minutes":"30"}}"""
                )
            )
        )

        assertEquals(4, display.lines.count { it.endsWith("（值格式无效，已隐藏）") })
        assertFalse(display.text().contains("秘密值"))
        assertFalse(display.text().contains("为开启"))
    }

    private fun context(
        arguments: JsonElement = JsonObject(emptyMap()),
        content: JsonElement? = null,
        approval: ToolApprovalState = ToolApprovalState.Pending,
        executed: Boolean = false,
    ) = ToolUIContext(
        tool = UIMessagePart.Tool(
            toolCallId = "config-test",
            toolName = "update_assistant_config",
            input = arguments.toString(),
            output = if (executed) listOf(UIMessagePart.Text(content?.toString() ?: "无效结果")) else emptyList(),
            approvalState = approval,
        ),
        arguments = arguments,
        content = content,
        loading = false,
    )

    private fun result(
        status: String,
        vararg changes: JsonObject,
        summary: String? = null,
        warnings: List<String> = emptyList(),
    ) = buildJsonObject {
        put("status", status)
        put("changes", JsonArray(changes.toList()))
        if (summary != null) put("summary", summary)
        put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
    }

    private fun change(field: String, value: JsonElement, summary: String? = null) = buildJsonObject {
        put("field", field)
        put("value", value)
        if (summary != null) put("summary", summary)
    }

    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)

    private fun AssistantConfigToolUI.Display.text(): String = (listOf(title) + lines + warnings).joinToString("\n")
}
