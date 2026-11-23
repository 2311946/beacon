package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.model.MemoryEmbeddingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantHealthToolTest {
    private val model = Model(
        modelId = "私有模型标识",
        displayName = "私有模型名称",
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        abilities = listOf(ModelAbility.TOOL),
    )
    private val otherModel = Model(modelId = "其他模型")
    private val target = Assistant(
        name = "私有角色名称",
        chatModelId = model.id,
        systemPrompt = "私有系统提示词",
        enableMemory = true,
        allowAiModifySettings = true,
    )
    private val other = Assistant(chatModelId = otherModel.id)
    private val provider = ProviderSetting.OpenAI(
        name = "私有提供商名称",
        models = listOf(model, otherModel),
        apiKey = "私有凭据",
        baseUrl = "https://secret.invalid/private",
    )
    private val initial = Settings(
        assistantId = other.id,
        assistants = listOf(target, other),
        providers = listOf(provider),
        chatModelId = otherModel.id,
        memoryEmbedding = MemoryEmbeddingSettings(enabled = true, apiKey = "私有向量凭据"),
    )
    private val conversation = Conversation(assistantId = other.id, messageNodes = emptyList())

    private inner class Harness(
        var settings: Settings = initial,
        val requestConversation: Conversation = conversation,
    ) {
        var reads = 0
        var conversationReads = 0
        var currentSettingsReads = 0
        var latestConversation: Conversation? = requestConversation
        var afterConversationRead: suspend () -> Unit = {}
        var mcpReads = 0
        val namespaces = mutableListOf<String>()
        val probes = mutableListOf<Pair<Model, ProviderSetting>>()
        var statuses: Map<Uuid, McpStatus> = emptyMap()
        var afterMemory: suspend () -> Unit = {}
        var memoryFailure: Exception? = null
        var readFailure: Exception? = null
        var probeFailure: Exception? = null
        var beforeRead: (Int) -> Unit = {}
        val tool = buildAssistantHealthToolWithCallbacks(
            assistantId = target.id,
            conversation = requestConversation,
            readSettings = {
                reads++
                beforeRead(reads)
                readFailure?.let { throw it }
                settings
            },
            countActiveMemoryFiles = { namespace ->
                namespaces += namespace
                afterMemory()
                memoryFailure?.let { throw it }
                3
            },
            readMcpStatuses = {
                mcpReads++
                statuses
            },
            probe = { selected, service ->
                probes += selected to service
                probeFailure?.let { throw it }
                "简化纯文本接口可用；原完整配置、图片与工具未经实测。"
            },
            readConversation = {
                conversationReads++
                afterConversationRead()
                latestConversation
            },
            readCurrentSettings = {
                currentSettingsReads++
                settings
            },
        )

        suspend fun call(input: String = "{}"): JsonObject {
            val parts = tool.execute(Json.parseToJsonElement(input))
            assertEquals(1, parts.size)
            val text = (parts.single() as UIMessagePart.Text).text
            assertTrue(text.toByteArray().size < 32 * 1024)
            return Json.parseToJsonElement(text).jsonObject
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.int

    @Test
    fun `严格布尔解析 缺省为false`() {
        assertFalse(parseAssistantHealthProbe(Json.parseToJsonElement("{}")))
        assertFalse(parseAssistantHealthProbe(Json.parseToJsonElement("{\"probe_connection\":false}")))
        assertTrue(parseAssistantHealthProbe(Json.parseToJsonElement("{\"probe_connection\":true}")))
        listOf("null", "[]", "false", "{\"probe_connection\":null}", "{\"probe_connection\":0}",
            "{\"probe_connection\":\"false\"}", "{\"probe_connection\":[]}", "{\"extra\":false}").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                parseAssistantHealthProbe(Json.parseToJsonElement(it))
            }
        }
    }

    @Test
    fun `固定工具名称与单字段schema 默认与false免审批 true和非法参数保守审批`() {
        val h = Harness()
        assertEquals("check_assistant_health", h.tool.name)
        val schema = h.tool.parameters() as InputSchema.Obj
        assertEquals(setOf("probe_connection"), schema.properties.keys)
        assertTrue(schema.required.isNullOrEmpty())
        assertEquals("boolean", schema.properties.getValue("probe_connection").jsonObject.text("type"))
        assertFalse(schema.properties.getValue("probe_connection").jsonObject.flag("default"))
        listOf("{}", "{\"probe_connection\":false}").forEach {
            assertFalse(h.tool.needsApproval(Json.parseToJsonElement(it)))
        }
        listOf("null", "[]", "true", "{\"probe_connection\":true}", "{\"probe_connection\":\"false\"}",
            "{\"probe_connection\":null}", "{\"probe_connection\":false,\"extra\":false}").forEach {
            assertTrue(h.tool.needsApproval(Json.parseToJsonElement(it)))
        }
        assertTrue(h.tool.description.contains("审批"))
        assertTrue(h.tool.description.contains("费用"))
        assertTrue(h.tool.description.contains("已移除自定义 header/body"))
        assertTrue(h.tool.description.contains("不保证原完整配置可用"))
        assertEquals(0, h.reads)
        assertEquals(0, h.mcpReads)
    }

    @Test
    fun `拒绝额外字段 不回显输入且不访问依赖`() = runBlocking {
        val h = Harness()
        val result = h.call("{\"probe_connection\":true,\"私有注入字段\":\"私有输入内容\"}")
        assertEquals("error", result.text("status"))
        assertFalse(result.toString().contains("私有"))
        assertEquals(0, h.reads)
        assertEquals(0, h.mcpReads)
        assertTrue(h.namespaces.isEmpty())
        assertTrue(h.probes.isEmpty())
    }

    @Test
    fun `默认只读固定角色 不使用当前角色或会话owner 不执行探测`() = runBlocking {
        val h = Harness()
        val result = h.call()
        assertEquals("configured", result.obj("model").text("status"))
        assertTrue(result.obj("model").flag("image_input_declared"))
        assertTrue(result.obj("model").flag("tool_calling_declared"))
        assertFalse(result.obj("model").flag("capability_tested"))
        assertEquals(listOf(target.id.toString()), h.namespaces)
        assertEquals(3, result.obj("memory").number("active_file_count"))
        assertEquals("personal", result.obj("memory").text("range"))
        assertEquals(target.localTools.size, result.obj("local_tools").number("count"))
        assertTrue(result.obj("local_tools").flag("allow_ai_modify_settings"))
        assertTrue(result.obj("embedding").flag("enabled"))
        assertFalse(result.obj("embedding").flag("tested"))
        assertEquals("not_requested", result.obj("connection").text("status"))
        assertEquals(1, h.reads)
        assertTrue(h.probes.isEmpty())
        assertFalse(result.toString().contains("私有"))
        assertFalse(result.toString().contains(target.id.toString()))
        assertFalse(result.toString().contains(provider.id.toString()))
    }

    @Test
    fun `每次执行读取最新设置而非创建时的配置`() = runBlocking {
        val h = Harness()
        assertTrue(h.call().obj("memory").flag("enabled"))
        h.settings = initial.copy(assistants = listOf(target.copy(enableMemory = false, chatModelId = otherModel.id), other))
        val result = h.call()
        assertFalse(result.obj("memory").flag("enabled"))
        assertFalse(result.obj("model").flag("image_input_declared"))
        assertEquals(1, h.namespaces.size)
        assertEquals(2, h.reads)
    }

    @Test
    fun `目标删除 重复或设置未加载均拒绝 不fallback其他角色`() = runBlocking {
        listOf(
            initial.copy(assistants = listOf(other)),
            initial.copy(assistants = listOf(target, target, other)),
            initial.copy(init = true),
        ).forEach { settings ->
            val h = Harness(settings)
            assertEquals("error", h.call("{\"probe_connection\":true}").text("status"))
            assertTrue(h.probes.isEmpty())
            assertTrue(h.namespaces.isEmpty())
        }
    }

    @Test
    fun `普通会话使用getChatModelOf保留会话模型快照`() {
        val snapshot = assistantHealthSnapshot(initial, target.id, conversation.copy(
            config = ConversationConfig(chatModelId = otherModel.id),
        ))
        assertEquals(otherModel.id, snapshot.model?.id)
        assertEquals(target.id, snapshot.assistant.id)
    }

    @Test
    fun `最新群配置覆盖旧临时模型 清除群模型后恢复成员模型而非owner`() {
        val request = conversation.copy(config = ConversationConfig(isGroupChat = true, chatModelId = otherModel.id))
        val group = GroupChat(conversationId = request.id, memberIds = listOf(target.id), chatModelId = otherModel.id)
        val withOverride = initial.copy(groupChats = listOf(group))
        assertEquals(otherModel.id, assistantHealthSnapshot(withOverride, target.id, request).model?.id)
        val cleared = initial.copy(groupChats = listOf(group.copy(chatModelId = null)))
        assertEquals(model.id, assistantHealthSnapshot(cleared, target.id, request).model?.id)
        assertEquals(model.id, assistantHealthSnapshot(initial, target.id, request).model?.id)
    }

    @Test
    fun `持久化群关联即使缺少群标记也选最新群模型并隔离个人记忆`() = runBlocking {
        val globalTarget = target.copy(useGlobalMemory = true)
        val group = GroupChat(conversationId = conversation.id, chatModelId = otherModel.id)
        val h = Harness(initial.copy(assistants = listOf(globalTarget, other), groupChats = listOf(group)))
        val result = h.call()
        assertEquals("group_personal", result.obj("memory").text("range"))
        assertEquals(listOf(target.id.toString()), h.namespaces)
        assertFalse(result.obj("model").flag("image_input_declared"))
    }

    @Test
    fun `单聊全局记忆只读全局namespace但输出不包含namespace`() = runBlocking {
        val h = Harness(initial.copy(assistants = listOf(target.copy(useGlobalMemory = true), other)))
        val result = h.call()
        assertEquals(listOf("__global__"), h.namespaces)
        assertEquals("global", result.obj("memory").text("range"))
        assertFalse(result.toString().contains("__global__"))
    }

    @Test
    fun `记忆关闭时绝不读库且不输出伪造计数`() = runBlocking {
        val h = Harness(initial.copy(assistants = listOf(target.copy(enableMemory = false), other)))
        h.memoryFailure = IllegalStateException("绝不应访问数据库")
        val result = h.call()
        assertEquals("disabled", result.obj("memory").text("status"))
        assertFalse(result.obj("memory").containsKey("active_file_count"))
        assertTrue(h.namespaces.isEmpty())
    }

    @Test
    fun `记忆读取失败仅输出固定错误不泄漏正文路径异常`() = runBlocking {
        val h = Harness()
        h.memoryFailure = IllegalStateException("私有正文 /private/memory.md 私有凭据")
        val result = h.call()
        assertEquals("error", result.obj("memory").text("status"))
        assertFalse(result.obj("memory").containsKey("active_file_count"))
        assertFalse(result.toString().contains("私有"))
        assertFalse(result.toString().contains("/private"))
        assertTrue(h.probes.isEmpty())
    }

    @Test
    fun `模型缺失 非CHAT 提供商关闭及覆盖关闭均不可探测`() = runBlocking {
        val invalid = listOf(
            initial.copy(providers = emptyList()),
            initial.copy(providers = listOf(provider.copy(enabled = false))),
            initial.copy(providers = listOf(provider.copy(models = listOf(model.copy(type = ModelType.EMBEDDING))))),
            initial.copy(providers = listOf(provider.copy(models = listOf(model.copy(type = ModelType.IMAGE))))),
            initial.copy(providers = listOf(provider.copy(models = listOf(model.copy(
                providerOverwrite = ProviderSetting.OpenAI(enabled = false),
            ))))),
            initial.copy(providers = listOf(provider, provider.copy(id = Uuid.random()))),
        )
        invalid.forEach { settings ->
            val h = Harness(settings)
            val result = h.call("{\"probe_connection\":true}")
            assertEquals("not_configured", result.obj("model").text("status"))
            assertEquals("unavailable", result.obj("connection").text("status"))
            assertTrue(h.probes.isEmpty())
        }
    }

    @Test
    fun `MCP只输出数量和安全状态枚举 不输出名称标识错误详情`() = runBlocking {
        val states = listOf(
            McpStatus.Connected, McpStatus.NeedsAuthorization, McpStatus.Idle,
            McpStatus.Connecting, McpStatus.Reconnecting(1, 5), McpStatus.Authorizing,
            McpStatus.Error("私有错误摘要", "私有堆栈与凭据"),
        )
        val servers = states.map {
            McpServerConfig.SseTransportServer(
                commonOptions = McpCommonOptions(name = "私有MCP名称", tools = listOf(
                    McpTool(name = "私有工具名称"), McpTool(enable = false, name = "私有关闭工具"),
                )),
                url = "https://secret.invalid/mcp",
            )
        }
        val disabled = McpServerConfig.SseTransportServer(commonOptions = McpCommonOptions(enable = false))
        val missing = Uuid.random()
        val h = Harness(initial.copy(
            assistants = listOf(target.copy(mcpServers = servers.map { it.id }.toSet() + disabled.id + missing), other),
            mcpServers = servers + disabled,
        ))
        h.statuses = servers.mapIndexed { i, server -> server.id to states[i] }.toMap()
        val result = h.call()
        val mcp = result.obj("mcp")
        assertEquals(9, mcp.number("server_count"))
        assertEquals(7, mcp.number("tool_count"))
        assertEquals(setOf("Connected", "NeedsAuthorization", "Idle", "Connecting", "Reconnecting", "Authorizing", "Error", "Disabled", "Unavailable"), mcp.obj("states").keys)
        mcp.obj("states").values.forEach { assertEquals(1, it.jsonPrimitive.int) }
        assertFalse(mcp.flag("connection_tested"))
        assertFalse(result.toString().contains("私有"))
        servers.forEach { assertFalse(result.toString().contains(it.id.toString())) }
    }

    @Test
    fun `探测前重读并仅调用当前model和有效provider 包含计费提示`() = runBlocking {
        val providerOverride = ProviderSetting.OpenAI(apiKey = "私有覆盖凭据")
        val configuredModel = model.copy(providerOverwrite = providerOverride)
        val h = Harness(initial.copy(providers = listOf(provider.copy(models = listOf(configuredModel, otherModel)))))
        val result = h.call("{\"probe_connection\":true}")
        assertEquals(2, h.reads)
        assertEquals(1, h.probes.size)
        assertEquals(model.id, h.probes.single().first.id)
        assertEquals(providerOverride.id, h.probes.single().second.id)
        assertEquals("completed", result.obj("connection").text("status"))
        assertTrue(result.obj("connection").text("notice").contains("可能产生费用"))
        assertTrue(result.obj("connection").text("notice").contains("简化纯文本"))
        assertTrue(result.obj("connection").text("notice").contains("已移除自定义 header/body"))
        assertTrue(result.obj("connection").text("notice").contains("不保证原完整配置可用"))
        assertFalse(result.obj("model").flag("capability_tested"))
        assertFalse(result.obj("embedding").flag("tested"))
        assertFalse(result.toString().contains("私有"))
    }

    @Test
    fun `记忆挂起后目标模型提供商变更删除或关闭均abort且不自动切换`() = runBlocking {
        val changed = listOf(
            initial.copy(assistants = listOf(other)),
            initial.copy(assistants = listOf(target, target, other)),
            initial.copy(assistants = listOf(target.copy(chatModelId = otherModel.id), other)),
            initial.copy(providers = listOf(provider.copy(apiKey = "已变更凭据"))),
            initial.copy(providers = listOf(provider.copy(baseUrl = "https://changed.invalid"))),
            initial.copy(providers = listOf(provider.copy(enabled = false))),
            initial.copy(providers = listOf(provider.copy(models = listOf(model.copy(modelId = "新模型标识"), otherModel)))),
        )
        changed.forEach { settings ->
            val h = Harness()
            h.afterMemory = { yield(); h.settings = settings }
            val result = h.call("{\"probe_connection\":true}")
            assertEquals("aborted", result.obj("connection").text("status"))
            assertTrue(h.probes.isEmpty())
            assertEquals(2, h.reads)
        }
    }

    @Test
    fun `每次执行读取当前会话模型和config而非创建时快照`() = runBlocking {
        val h = Harness()
        h.latestConversation = conversation.copy(config = ConversationConfig(
            chatModelId = otherModel.id,
            mcpServers = setOf(Uuid.random()),
        ))
        val local = h.call()
        assertFalse(local.obj("model").flag("image_input_declared"))
        assertEquals(1, local.obj("mcp").number("server_count"))
        assertEquals("completed", h.call("{\"probe_connection\":true}").obj("connection").text("status"))
        assertEquals(otherModel.id, h.probes.single().first.id)
        assertEquals(3, h.conversationReads)
    }

    @Test
    fun `记忆挂起后会话模型或config变更均中止而不探测旧模型`() = runBlocking {
        val request = conversation.copy(config = ConversationConfig(chatModelId = model.id))
        listOf(
            request.copy(config = request.config!!.copy(chatModelId = otherModel.id)),
            request.copy(config = request.config!!.copy(enableWebSearch = true)),
            request.copy(config = null),
        ).forEach { changed ->
            val h = Harness(requestConversation = request)
            h.afterMemory = { yield(); h.latestConversation = changed }
            assertEquals("aborted", h.call("{\"probe_connection\":true}").obj("connection").text("status"))
            assertTrue(h.probes.isEmpty())
            assertEquals(2, h.conversationReads)
        }
    }

    @Test
    fun `缺失或删除后同id新草稿不得诊断旧会话`() = runBlocking {
        val replacement = conversation.copy(createAt = conversation.createAt.plusSeconds(1), config = null)
        listOf(null, replacement, conversation.copy(id = Uuid.random())).forEach { changed ->
            val absent = Harness().apply { latestConversation = changed }
            assertEquals("error", absent.call().text("status"))
            assertTrue(absent.namespaces.isEmpty())
            assertTrue(absent.probes.isEmpty())
            val deleted = Harness()
            deleted.afterMemory = { yield(); deleted.latestConversation = changed }
            assertEquals("aborted", deleted.call("{\"probe_connection\":true}").obj("connection").text("status"))
            assertTrue(deleted.probes.isEmpty())
        }
    }

    @Test
    fun `最后会话读取挂起后重检模型config和最新Settings`() = runBlocking {
        val modelChanged = Harness()
        modelChanged.afterConversationRead = {
            if (modelChanged.conversationReads == 2) {
                yield()
                modelChanged.latestConversation = conversation.copy(config = ConversationConfig(chatModelId = otherModel.id))
            }
        }
        assertEquals("aborted", modelChanged.call("{\"probe_connection\":true}").obj("connection").text("status"))
        assertTrue(modelChanged.probes.isEmpty())
        val settingsChanged = Harness()
        settingsChanged.afterConversationRead = {
            if (settingsChanged.conversationReads == 2) {
                yield()
                settingsChanged.settings = initial.copy(providers = listOf(provider.copy(enabled = false)))
            }
        }
        assertEquals("aborted", settingsChanged.call("{\"probe_connection\":true}").obj("connection").text("status"))
        assertTrue(settingsChanged.probes.isEmpty())
        assertEquals(2, settingsChanged.reads)
        assertEquals(2, settingsChanged.conversationReads)
        assertEquals(2, settingsChanged.currentSettingsReads)
    }

    @Test
    fun `最新群会话owner模型不会替代固定发言角色模型`() = runBlocking {
        val group = GroupChat(conversationId = conversation.id, memberIds = listOf(target.id))
        val h = Harness(initial.copy(groupChats = listOf(group)))
        h.latestConversation = conversation.copy(config = ConversationConfig(isGroupChat = true, chatModelId = otherModel.id))
        assertEquals("completed", h.call("{\"probe_connection\":true}").obj("connection").text("status"))
        assertEquals(model.id, h.probes.single().first.id)
        assertEquals(listOf(target.id.toString()), h.namespaces)
    }

    @Test
    fun `测试回调未传实时读取参数仍兼容`() = runBlocking {
        var calls = 0
        val tool = buildAssistantHealthToolWithCallbacks(
            assistantId = target.id,
            conversation = conversation,
            readSettings = { initial },
            countActiveMemoryFiles = { 0 },
            readMcpStatuses = { emptyMap() },
            probe = { _, _ -> calls++; "简化纯文本接口可用" },
        )
        val output = tool.execute(Json.parseToJsonElement("{\"probe_connection\":true}"))
        val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("completed", result.obj("connection").text("status"))
        assertEquals(1, calls)
    }

    @Test
    fun `重读期间变更群模型同样中止而不使用旧临时快照`() = runBlocking {
        val group = GroupChat(conversationId = conversation.id, chatModelId = model.id)
        val h = Harness(initial.copy(groupChats = listOf(group)))
        h.beforeRead = { read ->
            if (read == 2) h.settings = h.settings.copy(groupChats = listOf(group.copy(chatModelId = otherModel.id)))
        }
        assertEquals("aborted", h.call("{\"probe_connection\":true}").obj("connection").text("status"))
        assertTrue(h.probes.isEmpty())
    }

    @Test
    fun `原地修改提供商也由冻结快照识别`() = runBlocking {
        val mutableProvider = provider.copy()
        val h = Harness(initial.copy(providers = listOf(mutableProvider)))
        h.afterMemory = { mutableProvider.apiKey = "原地更新的凭据" }
        assertEquals("aborted", h.call("{\"probe_connection\":true}").obj("connection").text("status"))
        assertTrue(h.probes.isEmpty())
    }

    @Test
    fun `设置读取与探测异常不回显原文`() = runBlocking {
        val readHarness = Harness()
        readHarness.readFailure = IllegalStateException("私有设置异常")
        val readResult = readHarness.call()
        assertEquals("error", readResult.text("status"))
        assertFalse(readResult.toString().contains("私有"))
        val probeHarness = Harness()
        probeHarness.probeFailure = IllegalStateException("私有服务端响应与凭据")
        val probeResult = probeHarness.call("{\"probe_connection\":true}")
        assertEquals("error", probeResult.obj("connection").text("status"))
        assertFalse(probeResult.toString().contains("私有"))
        assertTrue(probeResult.obj("connection").text("notice").contains("费用"))
    }

    @Test
    fun `取消在设置记忆重读和探测阶段均向上传播`() {
        val harnesses = listOf(
            Harness().apply { readFailure = CancellationException("读取取消") },
            Harness().apply { memoryFailure = CancellationException("记忆取消") },
            Harness().apply { beforeRead = { if (it == 2) throw CancellationException("重读取消") } },
            Harness().apply { afterConversationRead = { throw CancellationException("会话读取取消") } },
            Harness().apply {
                afterConversationRead = { if (conversationReads == 2) throw CancellationException("会话重读取消") }
            },
            Harness().apply { probeFailure = CancellationException("探测取消") },
        )
        harnesses.forEach { h ->
            assertThrows(CancellationException::class.java) {
                runBlocking { h.call("{\"probe_connection\":true}") }
            }
        }
    }

    @Test
    fun `重复群关联拒绝解析而不任选一个`() {
        val group = GroupChat(conversationId = conversation.id)
        assertThrows(IllegalStateException::class.java) {
            assistantHealthSnapshot(initial.copy(groupChats = listOf(group, group.copy(id = Uuid.random()))), target.id, conversation)
        }
    }

    @Test
    fun `快照缺失模型不返回其他角色模型`() {
        val missing = target.copy(chatModelId = Uuid.random())
        assertNull(assistantHealthSnapshot(initial.copy(assistants = listOf(missing, other)), target.id, conversation).model)
    }
}
