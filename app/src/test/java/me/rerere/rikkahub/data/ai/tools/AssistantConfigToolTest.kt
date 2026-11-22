package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AutonomousWakeupSettings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.GroupChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantConfigToolTest {
    private val target = Assistant(name = "目标角色", systemPrompt = "旧提示词", temperature = 0.5f, topP = 0.8f)
    private val other = Assistant(name = "其他角色", systemPrompt = "其他角色提示词")
    private val model = Model(modelId = "chat-alpha", displayName = "Alpha")
    private val secondModel = Model(modelId = "chat-beta", displayName = "Beta")
    private val provider = ProviderSetting.OpenAI(
        models = listOf(model, secondModel),
        apiKey = "秘密凭据不能回显",
        baseUrl = "https://secret.invalid/private-endpoint",
    )
    private val initial = Settings(
        assistantId = other.id,
        assistants = listOf(target, other),
        providers = listOf(provider),
        chatModelId = model.id,
        autonomousWakeup = AutonomousWakeupSettings(
            assistantIds = setOf(other.id),
            lastWakeupAt = mapOf(target.id.toString() to 123L, other.id.toString() to 456L),
        ),
    )

    private inner class Harness(
        var settings: Settings = initial,
        val conversation: Conversation = Conversation(assistantId = other.id, messageNodes = emptyList()),
    ) {
        var reads = 0
        var updates = 0
        var wakeupSyncs = 0
        val modelSyncs = mutableListOf<kotlin.uuid.Uuid>()
        val events = mutableListOf<String>()
        var beforeUpdate: () -> Unit = {}
        var readFailure: Exception? = null
        var beforeFailure: Exception? = null
        var persistFailure: Exception? = null
        var wakeupFailure: Exception? = null
        var modelFailure: Exception? = null
        var modelWarning: String? = null
        var skipTransform = false
        val tool: Tool = createAssistantConfigTool(
            assistantId = target.id,
            initialSettings = settings,
            conversation = conversation,
            readSettings = {
                reads++
                readFailure?.let { throw it }
                settings
            },
            updateSettings = { transform ->
                updates++
                beforeUpdate()
                beforeFailure?.let { throw it }
                if (!skipTransform) settings = transform(settings)
                events += "applied"
                persistFailure?.let { throw it }
                events += "persisted"
            },
            syncWakeup = {
                events += "wakeup"
                wakeupSyncs++
                wakeupFailure?.let { throw it }
            },
            syncConversationModel = { id ->
                events += "model"
                modelSyncs += id
                modelFailure?.let { throw it }
                modelWarning
            },
        )
        val assistant: Assistant get() = settings.assistants.single { it.id == target.id }

        suspend fun call(input: JsonElement): JsonObject {
            val parts = tool.execute(input)
            assertEquals(1, parts.size)
            val text = (parts.single() as UIMessagePart.Text).text
            assertTrue(text.length < 32 * 1024)
            assertTrue(text.toByteArray(Charsets.UTF_8).size < 32 * 1024)
            return Json.parseToJsonElement(text).jsonObject.also {
                assertEquals("next_turn", it.text("effective_from"))
                assertTrue(it["summary"] is JsonPrimitive)
                assertTrue(it["changes"] is JsonArray)
                assertTrue(it["warnings"] is JsonArray)
            }
        }

        suspend fun call(input: String = "{}") = call(Json.parseToJsonElement(input))

        fun assertNoSideEffects() {
            assertSame(initial, settings)
            assertEquals(0, updates)
            assertEquals(0, wakeupSyncs)
            assertTrue(modelSyncs.isEmpty())
        }
    }

    private fun JsonObject.text(field: String) = getValue(field).jsonPrimitive.content
    private fun JsonObject.changes() = getValue("changes").jsonArray.map { it.jsonObject }
    private fun JsonObject.warnings() = getValue("warnings").jsonArray.joinToString { it.jsonPrimitive.content }
    private fun modelInput(value: String) = buildJsonObject { put("model_id", value) }

    @Test
    fun `工具固定名称且所有输入均需审批 不暴露作用域输入`() {
        val h = Harness()
        assertEquals("update_assistant_config", h.tool.name)
        listOf(JsonNull, JsonPrimitive(true), JsonArray(emptyList()), buildJsonObject {}).forEach {
            assertTrue(h.tool.needsApproval(it))
        }
        val schema = h.tool.parameters() as InputSchema.Obj
        assertEquals(setOf("model_id", "temperature", "top_p", "system_prompt", "enable_memory", "wakeup"), schema.properties.keys)
        assertTrue(schema.required.isNullOrEmpty())
        assertEquals(listOf("number", "null"), schema.properties.getValue("temperature").jsonObject
            .getValue("type").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("2", schema.properties.getValue("temperature").jsonObject.text("maximum"))
        assertEquals("1", schema.properties.getValue("top_p").jsonObject.text("maximum"))
        assertEquals("16000", schema.properties.getValue("system_prompt").jsonObject.text("maxLength"))
        val wakeup = schema.properties.getValue("wakeup").jsonObject
        assertEquals("false", wakeup.text("additionalProperties"))
        val nested = wakeup.getValue("properties").jsonObject
        assertEquals(setOf("participating", "global_enabled", "interval_minutes", "start_hour", "start_minute", "end_hour", "end_minute", "decision_prompt"), nested.keys)
        assertEquals(listOf("15", "30", "60"), nested.getValue("interval_minutes").jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(h.tool.description.contains("审批"))
        assertTrue(h.tool.description.contains("影响全部参与角色"))
        assertTrue(nested.getValue("decision_prompt").jsonObject.text("description").contains("全局"))
        assertEquals(0, h.reads)
        h.assertNoSideEffects()
    }

    @Test
    fun `未知字段和越权配置整包拒绝且不回显输入值`() = runBlocking {
        val names = listOf("assistant_id", "assistantId", "scope", "providers", "providerOverwrite", "api_key", "apiKey", "api_url", "base_url", "baseUrl", "endpoints", "billing", "budget", "payment", "chatModelId")
        names.forEach { name ->
            val h = Harness()
            val result = h.call(buildJsonObject {
                put("temperature", 0.9)
                put(name, "攻击字段秘密值")
            })
            assertEquals("error", result.text("status"))
            assertFalse(result.toString().contains("攻击字段秘密值"))
            assertFalse(result.toString().contains(name))
            assertTrue(result.changes().isEmpty())
            assertEquals(0, h.reads)
            h.assertNoSideEffects()
        }
        listOf("api_key", "endpoints", "billing", "assistant_ids", "enabled", "lastWakeupAt").forEach { name ->
            val h = Harness()
            val result = h.call(buildJsonObject {
                put("temperature", 0.9)
                put("wakeup", buildJsonObject { put("participating", true); put(name, "嵌套秘密值") })
            })
            assertEquals("error", result.text("status"))
            assertFalse(result.toString().contains("嵌套秘密值"))
            assertFalse(result.toString().contains(name))
            h.assertNoSideEffects()
        }
    }

    @Test
    fun `所有错误类型和非法 null 拒绝整个 patch 且无副作用`() = runBlocking {
        val invalid = listOf(
            "null", "[]", "true", "1", "\"字符串\"",
            "{\"temperature\":\"0.9\"}", "{\"top_p\":true}",
            "{\"temperature\":{\"api_key\":\"秘密值\"}}", "{\"top_p\":[]}",
            "{\"model_id\":null}", "{\"model_id\":1}", "{\"model_id\":{\"endpoints\":[]}}",
            "{\"system_prompt\":null}", "{\"system_prompt\":3}", "{\"system_prompt\":{\"billing\":true}}",
            "{\"enable_memory\":null}", "{\"enable_memory\":\"true\"}", "{\"enable_memory\":1}",
            "{\"wakeup\":null}", "{\"wakeup\":[]}",
            "{\"wakeup\":{\"participating\":null}}", "{\"wakeup\":{\"global_enabled\":\"true\"}}",
            "{\"wakeup\":{\"interval_minutes\":30.0}}", "{\"wakeup\":{\"start_hour\":\"7\"}}",
            "{\"wakeup\":{\"decision_prompt\":null}}", "{\"wakeup\":{\"end_hour\":false}}",
        )
        invalid.forEach { input ->
            val h = Harness()
            assertEquals(input, "error", h.call(input).text("status"))
            assertEquals(0, h.reads)
            h.assertNoSideEffects()
        }
    }

    @Test
    fun `浮点先严格验证原始边界 不能由舍入将非法值变成合法值`() = runBlocking {
        val invalid = listOf(
            "temperature" to "2.000000000000000000001",
            "temperature" to "2.00000001",
            "temperature" to "-0.00000000000000000001",
            "temperature" to "-1e-9999",
            "temperature" to "1e9999",
            "top_p" to "1.000000000000000000001",
            "top_p" to "1.00000001",
            "top_p" to "-0.000000001",
        )
        invalid.forEach { (field, value) ->
            val h = Harness()
            assertEquals("error", h.call("{\"$field\":$value}").text("status"))
            h.assertNoSideEffects()
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            val h = Harness()
            assertEquals("error", h.call(buildJsonObject { put("temperature", JsonPrimitive(value)) }).text("status"))
            h.assertNoSideEffects()
        }
        listOf(0.0, 2.0, 0.9, 1e-20).forEach { value ->
            val h = Harness()
            assertEquals("saved", h.call(buildJsonObject { put("temperature", value) }).text("status"))
            assertEquals(value.toFloat(), h.assistant.temperature)
        }
        listOf(0.0, 1.0).forEach { value ->
            val h = Harness()
            assertEquals("saved", h.call(buildJsonObject { put("top_p", value) }).text("status"))
            assertEquals(value.toFloat(), h.assistant.topP)
        }
    }

    @Test
    fun `null 仅重置温度和 top p 省略保持其他字段`() = runBlocking {
        val h = Harness()
        val result = h.call("{\"temperature\":null,\"top_p\":null}")
        assertEquals("saved", result.text("status"))
        assertEquals(target.copy(temperature = null, topP = null), h.assistant)
        assertEquals(setOf("temperature", "top_p"), result.changes().map { it.text("field") }.toSet())
        assertTrue(result.changes().all { it["value"] == JsonNull })
        assertEquals(initial.autonomousWakeup, h.settings.autonomousWakeup)
        assertEquals(0, h.wakeupSyncs)
        assertTrue(h.modelSyncs.isEmpty())
    }

    @Test
    fun `目标取固定角色而非当前角色或群 owner 且逐字段保留其他设置`() = runBlocking {
        val h = Harness(conversation = Conversation(
            assistantId = other.id, messageNodes = emptyList(), config = ConversationConfig(isGroupChat = true),
        ))
        val result = h.call("{\"temperature\":0.9,\"top_p\":0.6,\"system_prompt\":\"实际新提示词\",\"enable_memory\":true}")
        assertEquals("saved", result.text("status"))
        assertEquals(initial.copy(assistants = listOf(target.copy(
            temperature = 0.9f, topP = 0.6f, systemPrompt = "实际新提示词", enableMemory = true,
        ), other)), h.settings)
        assertSame(other, h.settings.assistants.last())
        assertSame(initial.providers, h.settings.providers)
        assertEquals(other.id, h.settings.assistantId)
        assertTrue(result.text("summary").contains("已修改温度为0.9"))
        assertTrue(result.text("summary").contains("已修改系统提示词为实际新提示词"))
        assertEquals("实际新提示词", result.changes().single { it.text("field") == "system_prompt" }.text("value"))
        assertTrue(result.warnings().contains("本轮不会热更新"))
        assertTrue(result.changes().all { it.keys == setOf("field", "label", "value", "summary") })
        assertEquals(0, h.wakeupSyncs)
        assertTrue(h.modelSyncs.isEmpty())
    }

    @Test
    fun `最新闭包合并并发字段变化 不写回初始或预检快照`() = runBlocking {
        val h = Harness()
        val latest = initial.copy(
            assistantId = target.id,
            assistants = listOf(target.copy(systemPrompt = "并发新提示词", topP = 0.3f), other.copy(name = "并发改名")),
            autonomousWakeup = initial.autonomousWakeup.copy(lastWakeupAt = mapOf("并发时间" to 999L)),
            favoriteModels = listOf(secondModel.id),
        )
        h.beforeUpdate = { h.settings = latest }
        val result = h.call("{\"temperature\":0.9}")
        assertEquals("saved", result.text("status"))
        assertEquals(latest.copy(assistants = latest.assistants.map {
            if (it.id == target.id) it.copy(temperature = 0.9f) else it
        }), h.settings)
        assertEquals(listOf("temperature"), result.changes().map { it.text("field") })
    }

    @Test
    fun `目标删除或重复身份不 fallback 最新闭包删除时也不写入`() = runBlocking {
        listOf(emptyList(), listOf(other), listOf(target, target, other)).forEach { assistants ->
            val h = Harness(initial.copy(assistants = assistants))
            assertEquals("error", h.call("{\"temperature\":0.9}").text("status"))
            assertEquals(0, h.updates)
            assertEquals(assistants, h.settings.assistants)
        }
        val h = Harness()
        val deleted = initial.copy(assistants = listOf(other))
        h.beforeUpdate = { h.settings = deleted }
        assertEquals("error", h.call("{\"temperature\":0.9}").text("status"))
        assertSame(deleted, h.settings)
        assertEquals(0, h.wakeupSyncs)
        assertTrue(h.modelSyncs.isEmpty())
    }

    @Test
    fun `空 patch 和同值 patch 不谎称已修改 不产生同步副作用`() = runBlocking {
        listOf("{}", "{\"wakeup\":{}}", "{\"temperature\":0.5,\"system_prompt\":\"旧提示词\"}").forEach { input ->
            val h = Harness()
            val result = h.call(input)
            assertEquals("unchanged", result.text("status"))
            assertTrue(result.changes().isEmpty())
            assertFalse(result.text("summary").contains("已修改"))
            assertSame(initial, h.settings)
            assertTrue(h.modelSyncs.isEmpty())
            assertEquals(0, h.wakeupSyncs)
            if (input != "{\"temperature\":0.5,\"system_prompt\":\"旧提示词\"}") assertEquals(0, h.updates)
        }
        val h = Harness()
        h.beforeUpdate = { h.settings = initial.copy(assistants = listOf(target.copy(temperature = 0.9f), other)) }
        val result = h.call("{\"temperature\":0.9}")
        assertEquals("unchanged", result.text("status"))
        assertTrue(result.changes().isEmpty())
    }

    @Test
    fun `只接受已配置 CHAT 模型 UUID 或唯一名称 并在保存后同步`() = runBlocking {
        listOf(model.id.toString(), model.modelId, model.displayName).forEach { reference ->
            val h = Harness()
            val result = h.call(modelInput(reference))
            assertEquals("saved", result.text("status"))
            assertEquals(model.id, h.assistant.chatModelId)
            assertEquals(listOf(model.id), h.modelSyncs)
            assertEquals(listOf("applied", "persisted", "model"), h.events)
            assertEquals(model.id.toString(), result.changes().single().text("value"))
            assertSame(initial.providers, h.settings.providers)
        }
    }

    @Test
    fun `UUID 优先而非与名称别名冲突且歧义名称拒绝`() = runBlocking {
        val aliasCollision = secondModel.copy(modelId = model.id.toString(), displayName = "Alpha")
        val state = initial.copy(providers = listOf(provider.copy(models = listOf(model, aliasCollision))))
        val h = Harness(state)
        assertEquals("saved", h.call(modelInput(model.id.toString())).text("status"))
        assertEquals(model.id, h.assistant.chatModelId)
        val ambiguous = Harness(state)
        assertEquals("error", ambiguous.call(modelInput("Alpha")).text("status"))
        assertEquals(0, ambiguous.updates)
        val duplicateId = Harness(initial.copy(providers = listOf(provider.copy(models = listOf(model, model)))))
        assertEquals("error", duplicateId.call(modelInput(model.id.toString())).text("status"))
        assertEquals(0, duplicateId.updates)
    }

    @Test
    fun `未配置 非CHAT 禁用提供商 禁用覆盖模型均不可用且不回显凭据`() = runBlocking {
        val invalidStates = listOf(
            initial.copy(providers = emptyList()),
            initial.copy(providers = listOf(provider.copy(enabled = false))),
            initial.copy(providers = listOf(provider.copy(models = listOf(model.copy(type = ModelType.IMAGE))))),
            initial.copy(providers = listOf(provider.copy(models = listOf(model.copy(type = ModelType.EMBEDDING))))),
            initial.copy(providers = listOf(provider.copy(models = listOf(model.copy(
                providerOverwrite = provider.copy(enabled = false),
            ))))),
        )
        invalidStates.forEach { settings ->
            val h = Harness(settings)
            val result = h.call(buildJsonObject { put("model_id", model.id.toString()); put("temperature", 0.9) })
            assertEquals("error", result.text("status"))
            assertSame(settings, h.settings)
            assertEquals(0, h.updates)
            assertTrue(h.modelSyncs.isEmpty())
            assertFalse(result.toString().contains(provider.apiKey))
        }
        val h = Harness()
        val result = h.call(modelInput("秘密输入不得回显"))
        assertFalse(result.toString().contains("秘密输入不得回显"))
        h.assertNoSideEffects()
    }

    @Test
    fun `最新设置再次验证模型 防止并发删除禁用或名称变歧义`() = runBlocking {
        val providers = listOf(
            emptyList(),
            listOf(provider.copy(enabled = false)),
            listOf(provider.copy(models = listOf(secondModel))),
            listOf(provider.copy(models = listOf(model, secondModel.copy(displayName = model.displayName)))),
        )
        providers.forEach { latestProviders ->
            val h = Harness()
            h.beforeUpdate = { h.settings = initial.copy(providers = latestProviders) }
            val result = h.call(buildJsonObject { put("model_id", "Alpha"); put("temperature", 0.9) })
            assertEquals("error", result.text("status"))
            assertEquals(target, h.assistant)
            assertTrue(h.modelSyncs.isEmpty())
            assertEquals(0, h.wakeupSyncs)
        }
    }

    @Test
    fun `同值模型仍同步当前会话但不声称修改了默认设置`() = runBlocking {
        val h = Harness(initial.copy(assistants = listOf(target.copy(chatModelId = model.id), other)))
        val result = h.call(modelInput(model.id.toString()))
        assertEquals("unchanged", result.text("status"))
        assertTrue(result.changes().isEmpty())
        assertFalse(result.text("summary").contains("已修改"))
        assertEquals(listOf(model.id), h.modelSyncs)
        assertEquals(listOf("applied", "persisted", "model"), h.events)
    }

    @Test
    fun `默认模型同值但当前会话模型不同也展示会话修改成功`() = runBlocking {
        val h = Harness(
            initial.copy(assistants = listOf(target.copy(chatModelId = model.id), other)),
            Conversation(assistantId = target.id, messageNodes = emptyList(), config = ConversationConfig(chatModelId = secondModel.id)),
        )
        val result = h.call(modelInput(model.id.toString()))
        assertEquals("saved", result.text("status"))
        assertTrue(result.changes().isEmpty())
        assertTrue(result.text("summary").contains("已修改当前会话模型为${model.id}"))
        assertEquals(listOf(model.id), h.modelSyncs)
    }

    @Test
    fun `提示中只公开可用模型名称和标识 不序列化模型或设置`() {
        val overwritten = model.copy(providerOverwrite = provider.copy(apiKey = "覆盖秘密凭据"))
        val disabled = provider.copy(enabled = false, models = listOf(Model(modelId = "禁用模型")))
        val image = Model(modelId = "图像模型", type = ModelType.IMAGE)
        val h = Harness(initial.copy(providers = listOf(provider.copy(models = listOf(overwritten, image)), disabled)))
        val prompt = h.tool.systemPrompt(model, emptyList())
        assertTrue(prompt.contains(model.id.toString()))
        assertTrue(prompt.contains(model.modelId))
        assertTrue(prompt.contains(model.displayName))
        assertTrue(prompt.contains("wakeup.participating"))
        assertTrue(prompt.contains("wakeup.interval_minutes"))
        assertFalse(prompt.contains("lastWakeupAt"))
        listOf(provider.apiKey, provider.baseUrl, "覆盖秘密凭据", "providerOverwrite", "apiKey", "quotaQuery", "禁用模型", "图像模型", other.systemPrompt).forEach {
            assertFalse(it, prompt.contains(it))
            assertFalse(it, h.tool.description.contains(it))
        }
        h.settings = initial.copy(providers = emptyList())
        assertTrue(h.tool.systemPrompt(model, emptyList()).contains(model.id.toString()))
        assertEquals(0, h.reads)
        assertEquals(0, h.updates)
    }

    @Test
    fun `唤醒仅目标加入退出 全局关闭不暗开且保留其他成员和运行时间`() = runBlocking {
        val h = Harness()
        val result = h.call("{\"wakeup\":{\"participating\":true}}")
        assertEquals("saved", result.text("status"))
        assertFalse(h.settings.autonomousWakeup.enabled)
        assertEquals(setOf(target.id, other.id), h.settings.autonomousWakeup.assistantIds)
        assertEquals(initial.autonomousWakeup.lastWakeupAt, h.settings.autonomousWakeup.lastWakeupAt)
        assertTrue(result.warnings().contains("未自动开启全局"))
        assertEquals(listOf("wakeup.participating"), result.changes().map { it.text("field") })
        assertEquals(1, h.wakeupSyncs)
        val leave = h.call("{\"wakeup\":{\"participating\":false}}")
        assertEquals("saved", leave.text("status"))
        assertEquals(setOf(other.id), h.settings.autonomousWakeup.assistantIds)
        assertEquals(initial.autonomousWakeup.lastWakeupAt, h.settings.autonomousWakeup.lastWakeupAt)
        assertEquals(2, h.wakeupSyncs)
    }

    @Test
    fun `显式全局唤醒字段保存并明确全部参与角色影响`() = runBlocking {
        val h = Harness()
        val result = h.call("""{"wakeup":{"global_enabled":true,"interval_minutes":15,"start_hour":0,"start_minute":59,"end_hour":23,"end_minute":0,"decision_prompt":"新全局决策"}}""")
        assertEquals("saved", result.text("status"))
        assertEquals(initial.autonomousWakeup.copy(
            enabled = true, intervalMinutes = 15, startHour = 0, startMinute = 59,
            endHour = 23, endMinute = 0, decisionPrompt = "新全局决策",
        ), h.settings.autonomousWakeup)
        assertEquals(setOf(other.id), h.settings.autonomousWakeup.assistantIds)
        assertTrue(result.text("summary").contains("全局唤醒开关（影响全部参与角色）为true"))
        assertTrue(result.warnings().contains("影响全部参与角色"))
        assertEquals(1, h.wakeupSyncs)
        assertEquals(listOf("applied", "persisted", "wakeup"), h.events)
        assertEquals("saved", h.call("{\"wakeup\":{\"global_enabled\":false}}").text("status"))
        assertFalse(h.settings.autonomousWakeup.enabled)
    }

    @Test
    fun `并发唤醒更新保留最新成员记录和省略字段`() = runBlocking {
        val h = Harness()
        val third = Assistant()
        val latest = initial.autonomousWakeup.copy(
            enabled = true, assistantIds = setOf(other.id, third.id), endHour = 22,
            decisionPrompt = "并发全局决策", lastWakeupAt = mapOf("最新记录" to 888L),
        )
        h.beforeUpdate = { h.settings = initial.copy(autonomousWakeup = latest) }
        h.call("{\"wakeup\":{\"participating\":true,\"interval_minutes\":60}}")
        assertEquals(latest.copy(assistantIds = latest.assistantIds + target.id, intervalMinutes = 60), h.settings.autonomousWakeup)
    }

    @Test
    fun `唤醒数字边界和非法枚举整包拒绝`() = runBlocking {
        val invalid = listOf(
            "interval_minutes" to "0", "interval_minutes" to "16", "interval_minutes" to "90",
            "start_hour" to "-1", "start_hour" to "24", "end_hour" to "24",
            "start_minute" to "-1", "start_minute" to "60", "end_minute" to "60",
            "end_minute" to "1.5", "end_minute" to "null", "end_minute" to "9999999999999",
        )
        invalid.forEach { (field, value) ->
            val h = Harness()
            val result = h.call("{\"temperature\":0.9,\"wakeup\":{\"participating\":true,\"$field\":$value}}")
            assertEquals("error", result.text("status"))
            h.assertNoSideEffects()
        }
        val h = Harness()
        h.call("{\"wakeup\":{\"start_hour\":23,\"start_minute\":0,\"end_hour\":0,\"end_minute\":59}}")
        assertEquals(23, h.settings.autonomousWakeup.startHour)
        assertEquals(0, h.settings.autonomousWakeup.endHour)
    }

    @Test
    fun `持久化前失败不触发同步 不泄露异常细节`() = runBlocking {
        val h = Harness()
        h.beforeFailure = IllegalStateException("异常里的秘密凭据")
        val result = h.call(buildJsonObject {
            put("model_id", model.id.toString()); put("wakeup", buildJsonObject { put("global_enabled", true) })
        })
        assertEquals("error", result.text("status"))
        assertSame(initial, h.settings)
        assertFalse(result.toString().contains("异常里的秘密凭据"))
        assertTrue(h.modelSyncs.isEmpty())
        assertEquals(0, h.wakeupSyncs)
    }

    @Test
    fun `变换已应用但落盘失败必须报告可能已修改 不能声称回滚或同步成功`() = runBlocking {
        val h = Harness()
        h.persistFailure = IllegalStateException("异常中的地址和密钥不能暴露")
        val result = h.call(buildJsonObject {
            put("model_id", model.id.toString()); put("temperature", 0.9)
            put("wakeup", buildJsonObject { put("global_enabled", true) })
        })
        assertEquals("partial_or_unpersisted", result.text("status"))
        assertEquals(0.9f, h.assistant.temperature)
        assertEquals(model.id, h.assistant.chatModelId)
        assertTrue(h.settings.autonomousWakeup.enabled)
        assertTrue(result.warnings().contains("内存可能已改变"))
        assertTrue(result.warnings().contains("不能声称已经回滚"))
        assertFalse(result.text("summary").contains("已修改"))
        assertTrue(result.changes().all { it.text("summary").startsWith("待确认修改") })
        assertFalse(result.toString().contains("异常中的地址和密钥不能暴露"))
        assertTrue(h.modelSyncs.isEmpty())
        assertEquals(0, h.wakeupSyncs)
    }

    @Test
    fun `模型同步异常或返回警告均为 partial 且唤醒独立同步继续执行`() = runBlocking {
        listOf(false, true).forEach { throws ->
            val h = Harness()
            if (throws) h.modelFailure = IllegalStateException("不能回显的模型异常秘密")
            else h.modelWarning = "不能回显的服务返回秘密"
            val result = h.call(buildJsonObject {
                put("model_id", model.id.toString()); put("wakeup", buildJsonObject { put("global_enabled", true) })
            })
            assertEquals("partial", result.text("status"))
            assertEquals(model.id, h.assistant.chatModelId)
            assertTrue(result.warnings().contains("当前会话"))
            assertTrue(result.warnings().contains("同步"))
            assertFalse(result.toString().contains("不能回显"))
            assertEquals(1, h.wakeupSyncs)
            assertEquals(listOf("applied", "persisted", "model", "wakeup"), h.events)
        }
    }

    @Test
    fun `同值模型同步失败也不能报告 unchanged 成功`() = runBlocking {
        val h = Harness(initial.copy(assistants = listOf(target.copy(chatModelId = model.id), other)))
        h.modelFailure = IllegalStateException("失败")
        val result = h.call(modelInput(model.id.toString()))
        assertEquals("partial", result.text("status"))
        assertTrue(result.changes().isEmpty())
        assertFalse(result.text("summary").contains("已修改"))
        assertTrue(result.warnings().contains("同步失败"))
    }

    @Test
    fun `模型与唤醒同时同步失败均返回警告 不丢失已保存字段`() = runBlocking {
        val h = Harness()
        h.modelFailure = IllegalStateException("模型秘密")
        h.wakeupFailure = IllegalStateException("调度秘密")
        val result = h.call(buildJsonObject {
            put("model_id", model.id.toString())
            put("wakeup", buildJsonObject { put("participating", true); put("global_enabled", true) })
        })
        assertEquals("partial", result.text("status"))
        assertTrue(result.warnings().contains("当前会话模型同步失败"))
        assertTrue(result.warnings().contains("唤醒调度同步失败"))
        assertFalse(result.toString().contains("秘密"))
        assertEquals(model.id, h.assistant.chatModelId)
        assertEquals(setOf(target.id, other.id), h.settings.autonomousWakeup.assistantIds)
        assertEquals(1, h.wakeupSyncs)
    }

    @Test
    fun `唤醒同步失败为 partial 已保存设置不回滚`() = runBlocking {
        val h = Harness()
        h.wakeupFailure = IllegalStateException("调度秘密异常")
        val result = h.call("{\"wakeup\":{\"global_enabled\":true}}")
        assertEquals("partial", result.text("status"))
        assertTrue(h.settings.autonomousWakeup.enabled)
        assertTrue(result.warnings().contains("唤醒调度同步失败"))
        assertFalse(result.toString().contains("调度秘密异常"))
    }

    @Test
    fun `群聊无论配置标记还是关联均提示不修改共享快照且只更新固定角色`() = runBlocking {
        listOf(false, true).forEach { configMarked ->
            val conversation = Conversation(
                assistantId = other.id, messageNodes = emptyList(),
                config = ConversationConfig(chatModelId = secondModel.id, isGroupChat = configMarked),
            )
            val settings = if (configMarked) initial else initial.copy(
                groupChats = listOf(GroupChat(memberIds = listOf(other.id, target.id), conversationId = conversation.id)),
            )
            val h = Harness(settings, conversation)
            h.modelWarning = "群聊不改共享快照"
            val result = h.call(modelInput(model.id.toString()))
            assertEquals("saved", result.text("status"))
            assertTrue(result.warnings().contains("群聊"))
            assertTrue(result.warnings().contains("共享"))
            assertTrue(h.modelSyncs.isEmpty())
            assertEquals(secondModel.id, h.conversation.config?.chatModelId)
            assertEquals(model.id, h.assistant.chatModelId)
            assertSame(other, h.settings.assistants.last())
        }
    }

    @Test
    fun `会话独立提示词 override 保持原值并 warning`() = runBlocking {
        val conversation = Conversation(
            assistantId = target.id, messageNodes = emptyList(), customSystemPrompt = "会话独立提示词",
        )
        val h = Harness(conversation = conversation)
        val result = h.call("{\"system_prompt\":\"新的角色提示词\"}")
        assertEquals("saved", result.text("status"))
        assertEquals("新的角色提示词", h.assistant.systemPrompt)
        assertEquals("会话独立提示词", conversation.customSystemPrompt)
        assertTrue(result.warnings().contains("override"))
        assertTrue(result.warnings().contains("不会覆盖"))
        assertFalse(result.toString().contains("会话独立提示词"))
    }

    @Test
    fun `提示词长度限制和输出最坏转义边界 保留实际保存全文`() = runBlocking {
        listOf("system_prompt", "decision_prompt").forEach { field ->
            val h = Harness()
            val value = "文".repeat(16_001)
            val input = if (field == "system_prompt") buildJsonObject { put(field, value) } else {
                buildJsonObject { put("wakeup", buildJsonObject { put(field, value) }) }
            }
            assertEquals("error", h.call(input).text("status"))
            h.assertNoSideEffects()
        }
        listOf("文", "\u0000", "\"", "\\").forEach { character ->
            val h = Harness()
            val long = character.repeat(16_000)
            val result = h.call(buildJsonObject {
                put("system_prompt", long)
                put("wakeup", buildJsonObject { put("decision_prompt", long) })
            })
            assertEquals("saved", result.text("status"))
            assertEquals(long, h.assistant.systemPrompt)
            assertEquals(long, h.settings.autonomousWakeup.decisionPrompt)
            assertTrue(result.text("summary").contains("已截取前200字符"))
            result.changes().forEach {
                assertTrue(it.text("value").startsWith(character.repeat(512)))
                assertTrue(it.text("value").contains("已截取前512字符"))
            }
        }
    }

    @Test
    fun `读取失败 占位设置 存储未执行闭包均不谎称成功`() = runBlocking {
        val h = Harness()
        h.readFailure = IllegalStateException("读取秘密异常")
        val result = h.call("{\"temperature\":0.9}")
        assertEquals("error", result.text("status"))
        assertFalse(result.toString().contains("读取秘密异常"))
        h.assertNoSideEffects()
        val dummy = Harness(initial.copy(init = true))
        assertEquals("error", dummy.call("{\"temperature\":0.9}").text("status"))
        assertEquals(0, dummy.updates)
        val skipped = Harness()
        skipped.skipTransform = true
        assertEquals("error", skipped.call(modelInput(model.id.toString())).text("status"))
        assertTrue(skipped.modelSyncs.isEmpty())
        assertSame(initial, skipped.settings)
    }

    @Test
    fun `内部 patch 固定输入副本 可无持久化依赖测试`() {
        val wakeup = mutableMapOf<String, JsonElement>("participating" to JsonPrimitive(true))
        val input = mutableMapOf<String, JsonElement>(
            "temperature" to JsonPrimitive(0.9), "wakeup" to JsonObject(wakeup),
        )
        val patch = AssistantConfigPatch.parse(JsonObject(input))
        input["temperature"] = JsonPrimitive(1.5)
        wakeup["global_enabled"] = JsonPrimitive(true)
        val application = patch.applyTo(initial, target.id)
        assertEquals(0.9f, application.settings.assistants.first().temperature)
        assertFalse(application.settings.autonomousWakeup.enabled)
        assertEquals(setOf(target.id, other.id), application.settings.autonomousWakeup.assistantIds)
        assertEquals(listOf("temperature", "wakeup.participating"), application.changes.map { it.field })
        assertEquals(target, initial.assistants.first())
    }

    @Test
    fun `不同工具实例角色范围互不影响 即使当前设置选中另一个角色`() = runBlocking {
        var state = initial
        fun tool(id: kotlin.uuid.Uuid) = createAssistantConfigTool(
            assistantId = id,
            initialSettings = initial,
            conversation = Conversation(assistantId = other.id, messageNodes = emptyList()),
            readSettings = { state },
            updateSettings = { transform -> state = transform(state) },
            syncWakeup = {},
            syncConversationModel = { null },
        )
        val a = tool(target.id)
        val b = tool(other.id)
        a.execute(Json.parseToJsonElement("{\"temperature\":0.9}"))
        b.execute(Json.parseToJsonElement("{\"temperature\":1.2,\"wakeup\":{\"participating\":false}}"))
        assertEquals(listOf(target.copy(temperature = 0.9f), other.copy(temperature = 1.2f)), state.assistants)
        assertEquals(other.id, state.assistantId)
        assertTrue(state.autonomousWakeup.assistantIds.isEmpty())
    }

    @Test
    fun `同步使用最新闭包解析的模型 ID 而不是预检结果`() = runBlocking {
        val h = Harness()
        val replacement = secondModel.copy(displayName = model.displayName)
        h.beforeUpdate = { h.settings = initial.copy(providers = listOf(provider.copy(models = listOf(replacement)))) }
        val result = h.call(modelInput(model.displayName))
        assertEquals("saved", result.text("status"))
        assertEquals(replacement.id, h.assistant.chatModelId)
        assertEquals(listOf(replacement.id), h.modelSyncs)
    }

    @Test
    fun `协程取消继续传播不吞掉取消或回显异常`() {
        val h = Harness()
        h.readFailure = CancellationException("取消")
        assertThrows(CancellationException::class.java) { runBlocking { h.call("{\"temperature\":0.9}") } }
        h.assertNoSideEffects()
        val syncing = Harness()
        syncing.modelFailure = CancellationException("同步取消")
        assertThrows(CancellationException::class.java) { runBlocking { syncing.call(modelInput(model.id.toString())) } }
        assertEquals(model.id, syncing.assistant.chatModelId)
    }
}
