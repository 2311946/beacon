package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AssistantDiagnostics
import me.rerere.rikkahub.data.ai.chatMemoryId
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.withConversation
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.service.groupMemberRequestConversation
import kotlin.uuid.Uuid

private const val HEALTH_INPUT_ERROR = "参数无效；仅接受可选的 probe_connection 布尔值，未执行检查。"
private const val HEALTH_READ_ERROR = "无法读取目标角色配置，未发起连接检查。"
private const val HEALTH_ABORTED = "目标会话、角色、模型或提供商配置已变化或不可用，已终止连接检查；未自动切换模型或提供商。"
private const val HEALTH_BILLING_NOTICE = "连接检查会向当前模型提供商发送独立的简化纯文本请求，可能产生费用；" +
    "已移除自定义 header/body，不发送聊天历史；结果不保证原完整配置可用，不验证图片、工具或 embedding 能力。"

internal fun parseAssistantHealthProbe(input: JsonElement): Boolean {
    val fields = input as? JsonObject ?: throw IllegalArgumentException(HEALTH_INPUT_ERROR)
    require(fields.keys.all { it == "probe_connection" }) { HEALTH_INPUT_ERROR }
    if ("probe_connection" !in fields) return false
    return (fields["probe_connection"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: throw IllegalArgumentException(HEALTH_INPUT_ERROR)
}

/** 包含私有配置的快照只在本地使用，不直接序列化为工具输出。 */
internal class AssistantHealthSnapshot(
    val assistant: Assistant,
    val model: Model?,
    val provider: ProviderSetting?,
    val memoryNamespace: String,
    val memoryRange: String,
    val embeddingEnabled: Boolean,
    val mcp: JsonObject,
    private val probeIdentity: JsonElement,
) {
    fun hasSameProbeTarget(other: AssistantHealthSnapshot): Boolean = probeIdentity == other.probeIdentity
}

internal fun assistantHealthSnapshot(
    settings: Settings,
    assistantId: Uuid,
    conversation: Conversation,
    mcpStatuses: Map<Uuid, McpStatus> = emptyMap(),
): AssistantHealthSnapshot {
    check(!settings.init) { HEALTH_READ_ERROR }
    val target = checkNotNull(settings.assistants.singleOrNull { it.id == assistantId }) { HEALTH_READ_ERROR }
    val groups = settings.groupChats.filter { it.conversationId == conversation.id }
    check(groups.size <= 1) { HEALTH_READ_ERROR }
    val group = groups.singleOrNull()
    val isGroup = group != null || conversation.config?.isGroupChat == true
    val fixedConversation = conversation.copy(assistantId = assistantId)
    val requestConversation = if (isGroup) {
        // 不使用工具创建时的临时群模型快照，也不使用群会话 owner 的模型。
        fixedConversation.groupMemberRequestConversation(settings, assistantId, group?.chatModelId)
    } else {
        fixedConversation
    }
    val assistant = target.withConversation(requestConversation)
    val selectedModel = settings.getChatModelOf(requestConversation)
    val owners = selectedModel?.let { model -> settings.providers.filter { p -> p.models.any { it.id == model.id } } }.orEmpty()
    val owner = owners.singleOrNull()
    val model = selectedModel?.takeIf {
        owners.size == 1 && owner?.models?.count { candidate -> candidate.id == it.id } == 1 &&
            owner?.enabled == true && it.type == ModelType.CHAT && it.providerOverwrite?.enabled != false
    }
    val provider = model?.findProvider(settings.providers)?.takeIf { it.enabled }
    val namespace = settings.chatMemoryId(assistant, requestConversation)
    return AssistantHealthSnapshot(
        assistant = assistant,
        model = model,
        provider = provider,
        memoryNamespace = namespace,
        memoryRange = when {
            isGroup -> "group_personal"
            assistant.useGlobalMemory -> "global"
            else -> "personal"
        },
        embeddingEnabled = settings.memoryEmbedding.enabled,
        mcp = assistantHealthMcpSummary(settings, assistant, mcpStatuses),
        // 固化值而不是引用，提供商含可变字段；凭据、地址等变化也必须在外发前中止。
        probeIdentity = buildJsonObject {
            put("target", Json.encodeToJsonElement(Assistant.serializer(), target))
            put("conversation_owner", conversation.assistantId.toString())
            put("conversation_config", conversation.config?.let { Json.encodeToJsonElement(ConversationConfig.serializer(), it) } ?: JsonNull)
            put("model", model?.let { Json.encodeToJsonElement(Model.serializer(), it) } ?: JsonNull)
            put("owner", owner?.let { Json.encodeToJsonElement(ProviderSetting.serializer(), it) } ?: JsonNull)
            put("provider", provider?.let { Json.encodeToJsonElement(ProviderSetting.serializer(), it) } ?: JsonNull)
        },
    )
}

internal fun assistantHealthMcpSummary(
    settings: Settings,
    assistant: Assistant,
    statuses: Map<Uuid, McpStatus>,
): JsonObject {
    val counts = mutableMapOf<String, Int>()
    var toolCount = 0
    assistant.mcpServers.forEach { id ->
        val servers = settings.mcpServers.filter { it.id == id }
        val server = servers.singleOrNull()
        val status = when {
            server == null -> "Unavailable"
            !server.commonOptions.enable -> "Disabled"
            else -> {
                toolCount += server.commonOptions.tools.count { it.enable }
                when (statuses[id]) {
                    McpStatus.Idle, null -> "Idle"
                    McpStatus.Connecting -> "Connecting"
                    McpStatus.Connected -> "Connected"
                    is McpStatus.Reconnecting -> "Reconnecting"
                    is McpStatus.Error -> "Error"
                    McpStatus.NeedsAuthorization -> "NeedsAuthorization"
                    McpStatus.Authorizing -> "Authorizing"
                }
            }
        }
        counts[status] = (counts[status] ?: 0) + 1
    }
    return buildJsonObject {
        put("server_count", assistant.mcpServers.size)
        put("tool_count", toolCount)
        put("states", buildJsonObject { counts.toSortedMap().forEach { (state, count) -> put(state, count) } })
        put("connection_tested", false)
    }
}

fun buildAssistantHealthTool(
    assistantId: Uuid,
    conversation: Conversation,
    readSettings: suspend () -> Settings,
    memoryRepository: MemoryRepository,
    mcpManager: McpManager,
    diagnostics: AssistantDiagnostics,
    readConversation: suspend () -> Conversation?,
    readCurrentSettings: () -> Settings,
): Tool = buildAssistantHealthToolWithCallbacks(
    assistantId = assistantId,
    conversation = conversation,
    readSettings = readSettings,
    countActiveMemoryFiles = { namespace -> memoryRepository.getActiveFiles(namespace).size },
    readMcpStatuses = { mcpManager.syncingStatus.value },
    probe = { model, provider -> diagnostics.probe(model, provider, assistantId, conversation.id) },
    readConversation = readConversation,
    readCurrentSettings = readCurrentSettings,
)

/** 回调只用于隔离现有依赖；不提供连接 MCP、执行业务工具或写入配置的入口。 */
internal fun buildAssistantHealthToolWithCallbacks(
    assistantId: Uuid,
    conversation: Conversation,
    readSettings: suspend () -> Settings,
    countActiveMemoryFiles: suspend (String) -> Int,
    readMcpStatuses: () -> Map<Uuid, McpStatus>,
    probe: suspend (Model, ProviderSetting) -> String,
    readConversation: suspend () -> Conversation? = { conversation },
    readCurrentSettings: (() -> Settings)? = null,
): Tool = Tool(
    name = "check_assistant_health",
    description = "检查固定目标角色的当前配置。默认仅本地只读，不联网、不执行业务工具、不写数据；" +
        "图片输入与工具支持仅为模型配置声明，非实测。probe_connection 默认 false；" +
        "设为 true 必须先获用户审批，会向当前模型提供商发送独立的简化纯文本请求，可能产生费用；" +
        "已移除自定义 header/body，不保证原完整配置可用，不验证图片或工具能力；" +
        "不发送聊天历史，不探测 embedding 或 MCP，不自动更换模型或提供商。",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("probe_connection", buildJsonObject {
                put("type", "boolean")
                put("default", false)
                put("description", "默认 false，仅本地只读；true 需审批并可能产生模型调用费用。")
            })
        })
    },
    needsApproval = { input ->
        try {
            parseAssistantHealthProbe(input)
        } catch (_: IllegalArgumentException) {
            true
        }
    },
    execute = execute@{ input ->
        val wantsProbe = try {
            parseAssistantHealthProbe(input)
        } catch (_: IllegalArgumentException) {
            return@execute healthResult(buildJsonObject {
                put("status", "error")
                put("message", HEALTH_INPUT_ERROR)
            })
        }
        suspend fun readSnapshot(statuses: Map<Uuid, McpStatus> = emptyMap()): AssistantHealthSnapshot {
            val loadedSettings = readSettings()
            val latestConversation = checkNotNull(readConversation()) { HEALTH_READ_ERROR }
            // 删除后同 id 的 session 会换成新草稿，不能把它当成原会话继续诊断。
            check(latestConversation.id == conversation.id && latestConversation.createAt == conversation.createAt) { HEALTH_READ_ERROR }
            // 会话读取可能等待数据库；之后只同步读取设置，避免继续等待时错过会话配置更新。
            val settings = readCurrentSettings?.invoke() ?: loadedSettings
            return assistantHealthSnapshot(settings, assistantId, latestConversation, statuses)
        }
        try {
            currentCoroutineContext().ensureActive()
            val snapshot = readSnapshot(readMcpStatuses())
            val memory = buildJsonObject {
                put("enabled", snapshot.assistant.enableMemory)
                put("range", snapshot.memoryRange)
                if (snapshot.assistant.enableMemory) {
                    try {
                        put("active_file_count", countActiveMemoryFiles(snapshot.memoryNamespace))
                        put("status", "ok")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        put("status", "error")
                        put("message", "无法读取活跃记忆文件数量。")
                    }
                } else {
                    put("status", "disabled")
                }
            }
            val connection = if (!wantsProbe) {
                buildJsonObject { put("status", "not_requested") }
            } else if (snapshot.model == null || snapshot.provider == null) {
                buildJsonObject {
                    put("status", "unavailable")
                    put("notice", HEALTH_BILLING_NOTICE)
                    put("message", "未配置可用的 CHAT 模型及已启用提供商，未发起连接检查。")
                }
            } else {
                val latest = try {
                    readSnapshot()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    null
                }
                if (latest == null || !snapshot.hasSameProbeTarget(latest) || latest.model == null || latest.provider == null) {
                    buildJsonObject {
                        put("status", "aborted")
                        put("notice", HEALTH_BILLING_NOTICE)
                        put("message", HEALTH_ABORTED)
                    }
                } else {
                    // 最后一次挂起后校验最新会话与设置；此后不再等待 DB。
                    // 无跨 session/settings 的原子锁，校验至请求启动仍有常规并发窗口。
                    currentCoroutineContext().ensureActive()
                    try {
                        val result = probe(latest.model, latest.provider)
                        buildJsonObject {
                            put("status", "completed")
                            put("notice", HEALTH_BILLING_NOTICE)
                            put("message", result)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        buildJsonObject {
                            put("status", "error")
                            put("notice", HEALTH_BILLING_NOTICE)
                            put("message", "连接检查失败，无法确认纯文本接口可用。")
                        }
                    }
                }
            }
            healthResult(buildJsonObject {
                put("status", "ok")
                put("model", buildJsonObject {
                    put("status", if (snapshot.model != null && snapshot.provider != null) "configured" else "not_configured")
                    put("image_input_declared", snapshot.model?.inputModalities?.contains(Modality.IMAGE) == true)
                    put("tool_calling_declared", snapshot.model?.abilities?.contains(ModelAbility.TOOL) == true)
                    put("capability_tested", false)
                    put("note", "配置可用不代表接口可用；图片输入与工具支持仅配置声明，非实测。")
                })
                put("memory", memory)
                put("embedding", buildJsonObject {
                    put("enabled", snapshot.embeddingEnabled)
                    put("tested", false)
                })
                put("mcp", snapshot.mcp)
                put("local_tools", buildJsonObject {
                    put("count", snapshot.assistant.localTools.size)
                    put("allow_ai_modify_settings", snapshot.assistant.allowAiModifySettings)
                })
                put("connection", connection)
            })
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            healthResult(buildJsonObject {
                put("status", "error")
                put("message", if (wantsProbe) HEALTH_ABORTED else HEALTH_READ_ERROR)
                if (wantsProbe) put("notice", HEALTH_BILLING_NOTICE)
            })
        }
    },
)

private fun healthResult(payload: JsonObject): List<UIMessagePart> = listOf(UIMessagePart.Text(payload.toString()))
