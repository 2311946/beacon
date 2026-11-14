package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.withRetry
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.mediagen.provider.capabilities
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.StickerStore
import me.rerere.rikkahub.data.model.StickerReplyResolver
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "ChatRequestFactory"

internal const val VOICE_CALL_SYSTEM_INSTRUCTION = "你正在和用户进行语音通话，请用简短口语化的方式回复，像真的在打电话一样。不要写旁白、动作描写和内心独白，只说对话内容。每次回复控制在1-3句话。"

/**
 * 一轮对话的请求上下文，按会话固定的配置解析一次。
 *
 * 正常对话和压缩用同一份上下文组装请求，系统提示词、工具定义、参数和 sessionId 都相同，
 * 压缩请求的前缀才能和上一轮对话一致，命中提示词缓存。
 */
class ChatTurnContext(
    val settings: Settings,
    val assistant: Assistant,
    val model: Model,
    val providerSetting: ProviderSetting,
    val conversationId: Uuid,
    val conversationSystemPrompt: String?,
    val workspaceCwd: String?,
    val memories: List<MemoryFile>,
    val tools: List<Tool>,
    val stickerPrompt: String = "",
    val chatContext: String = "",
    val currentTimePrompt: String = formatCurrentChatTime(java.time.LocalDateTime.now()),
    // 每次组装请求重新检查，挂断后不再追加；不修改持久化角色设定。
    val isVoiceCallActive: () -> Boolean = { false },
    val groupSystemPrompt: String = "",
) {
    // 本轮请求尾部的临时 USER；不写入历史，不受旧历史快照冻结。工具步骤/重试复用。
    val requestContextMessage: UIMessage = createTurnUserContext(currentTimePrompt, chatContext)
}

/** 把会话变成发给模型的请求：系统提示词、上下文裁剪、输入输出转换都在这里 */
class ChatRequestFactory(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val chatToolFactory: ChatToolFactory,
    private val memoryRepository: MemoryRepository,
    templateTransformer: TemplateTransformer,
    workspaceRepository: WorkspaceRepository,
) {
    private val renderedPrefixes = me.rerere.rikkahub.data.ai.transformers.RenderedPrefixStore()

    private val inputTransformers: List<InputMessageTransformer> = listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        templateTransformer,
        WorkspaceReminderTransformer(workspaceRepository),
    )

    private val outputTransformers: List<OutputMessageTransformer> = listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )

    /** 解析本轮的助手、模型、记忆和工具；MCP 服务名不合法时抛出 InvalidMcpServerNamesException */
    suspend fun createContext(
        settings: Settings,
        conversation: Conversation,
        allowTools: Boolean = true,
        chatContext: String = "",
        isVoiceCallActive: () -> Boolean = { false },
        groupSystemPrompt: String = "",
    ): ChatTurnContext {
        // 模型、思考级别、搜索、工具等以会话上固定的配置为准
        val assistant = settings.getAssistantOf(conversation)
        val model = settings.getChatModelOf(conversation)
            ?: throw IllegalStateException("No chat model selected")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        return ChatTurnContext(
            settings = settings,
            assistant = assistant,
            model = model,
            providerSetting = provider,
            conversationId = conversation.id,
            chatContext = chatContext,
            groupSystemPrompt = groupSystemPrompt,
            isVoiceCallActive = isVoiceCallActive,
            stickerPrompt = StickerReplyResolver.buildPrompt(StickerStore.get(context).groups.value),
            conversationSystemPrompt = conversation.customSystemPrompt,
            workspaceCwd = conversation.workspaceCwd,
            memories = if (assistant.enableMemory) memoryRepository.getFiles(assistant.memoryId) else emptyList(),
            tools = chatToolFactory.createTools(
                settings = settings,
                assistant = assistant,
                model = model,
                workspaceCwd = conversation.workspaceCwd,
            ),
        )
    }

    /**
     * 本轮交给 runAgent 的请求和钩子。[messages] 的最后一条是助手消息时从它继续，否则新建一条助手消息。
     * [processingStatus] 用来显示 OCR 这类输入转换的进度。
     */
    fun createAgent(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?>,
    ): Pair<AgentRequest, AgentHooks> {
        val request = AgentRequest(
            provider = usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.CHAT, turn.assistant.id, turn.conversationId),
            ),
            providerSetting = turn.providerSetting,
            messages = messages,
            tools = turn.tools,
            stream = turn.assistant.streamOutput,
            retry = turn.settings.retryPolicy(),
        )
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                Log.i(TAG, "prepareStep: ${turn.model.id}")
                return buildStep(turn, messages, processingStatus)
            }

            override suspend fun previewStep(message: UIMessage, newPartsFrom: Int) =
                message.transformAssistantStatusReply(newPartsFrom, generationFinished = false) {
                    it.visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }

            override suspend fun finishStep(message: UIMessage, newPartsFrom: Int): UIMessage {
                if (me.rerere.rikkahub.BuildConfig.DEBUG) {
                    // 临时 DEBUG 诊断：记录转换前的完整正文和匹配统计。
                    val raw = message.parts.drop(newPartsFrom).filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    // 用户要求排查完整原始回复；DEBUG 下不按标记格式过滤，以免漏掉异常格式。
                    // 日志可能包含私密聊天与签名URL，取证完成后应移除此诊断。
                    Log.d("StickerReplyRaw", "[begin] conversation=${turn.conversationId} message=${message.id}")
                    raw.chunked(1500).forEachIndexed { index, chunk ->
                        Log.d("StickerReplyRaw", "[part=$index] $chunk")
                    }
                    Log.d("StickerReplyRaw", "[end] message=${message.id}")
                    val groups = StickerStore.get(context).groups.value
                    val urls = groups.flatMap { it.stickers }.map { it.url }.distinct()
                    val marker = Regex("[\\[【](?:图片|表情包?|image|sticker)[ \\t]*[:：]", RegexOption.IGNORE_CASE)
                    Log.d("StickerReply", "[raw-response] conversation=${turn.conversationId} chars=${raw.length} markers=${marker.findAll(raw).count()} knownUrls=${urls.count { raw.contains(it) }} urlLike=${raw.contains("http", ignoreCase = true)} displayConversion=${StickerReplyResolver.resolve(raw, groups) != raw}")
                }
                return message.transformAssistantStatusReply(newPartsFrom, generationFinished = true) {
                    it.transforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .onGenerationFinish(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }.also { final ->
                    if (me.rerere.rikkahub.BuildConfig.DEBUG) {
                        val before = message.toText()
                        val after = final.toText()
                        Log.d("StickerReply", "[finish-transform] message=${message.id} beforeChars=${before.length} afterChars=${after.length} changed=${before != after}")
                    }
                }
            }
        }
        return request to hooks
    }

    /**
     * 生成压缩摘要。
     *
     * 请求按正常对话组装（系统提示词、工具定义、参数和 sessionId 都相同），只在末尾追加压缩指令，
     * 前缀与上一轮对话一致，可以命中提示词缓存，模型也能看到工具调用和结果。
     * 工具定义必须保留：它属于请求前缀，去掉或改动 tool_choice 都会让缓存失效。
     */
    suspend fun generateCompressionSummary(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        compressPrompt: String,
    ): String {
        val (requestMessages, params) = buildStep(turn, messages)
        // 压缩指令不经过输入转换器，模板转换器会把其中的花括号当成模板语法
        val result = withRetry(
            policy = turn.settings.retryPolicy(),
            onRetry = { Log.w(TAG, "Compression request failed, retrying in ${it.delay}", it.cause) },
        ) {
            usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.COMPRESSION, turn.assistant.id, turn.conversationId),
            ).generateText(
                providerSetting = turn.providerSetting,
                messages = requestMessages + UIMessage.user(compressPrompt),
                // 助手的输出上限是给聊天回复定的，结构化摘要写不完会被截断；max_tokens 不属于请求前缀，改了不影响缓存
                params = params.copy(maxTokens = null),
            )
        }
        val summary = result.message.compressionSummaryOrNull()
            ?: error(context.getString(R.string.chat_page_compress_tool_call_response))
        return summary.ifBlank { error("Failed to generate compressed summary") }
    }

    /** 群总结采用中立记录员，不带角色记忆、私聊桥接或活动生成指令，不受聊天条数裁剪。 */
    suspend fun generateGroupSummary(
        settings: Settings,
        conversationId: Uuid,
        assistant: Assistant,
        model: Model,
        transcript: String,
        additionalPrompt: String,
        merge: Boolean = false,
    ): List<me.rerere.rikkahub.data.model.GroupSummaryTopic> {
        val provider = model.findProvider(settings.providers) ?: error("总结模型的服务商不存在")
        val result = withRetry(
            policy = settings.retryPolicy(),
            onRetry = { Log.w(TAG, "Group summary retry", it.cause) },
        ) {
            usageRecorder.wrap(
                providerManager.getProviderByType(provider),
                UsageTag(UsageSource.COMPRESSION, assistant.id, conversationId),
            ).generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.system(buildGroupSummaryPrompt(additionalPrompt, merge)),
                    UIMessage.user(transcript),
                ),
                params = backgroundTextGenerationParams(model.copy(tools = emptySet()), conversationId, ReasoningLevel.OFF).copy(
                    temperature = 0.2f,
                    maxTokens = 8192,
                    customHeaders = assistant.customHeaders + model.customHeaders,
                    customBody = assistant.customBodies + model.customBodies,
                ),
            )
        }
        check(result.finishReason?.lowercase() !in setOf("length", "max_tokens", "max_output_tokens", "incomplete")) {
            "总结输出被截断，未应用压缩，请缩小总结范围或更换模型"
        }
        val text = result.message.compressionSummaryOrNull() ?: error("总结请求返回了工具调用，未应用压缩")
        return parseGroupSummaryTopics(text)
    }

    /** 一步请求实际发出的消息和参数；正常对话和压缩共用，二者的请求前缀必须一致才能命中提示词缓存 */
    private suspend fun buildStep(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    ): StepRequest {
        val assistant = turn.assistant
        val voiceCallActive = turn.isVoiceCallActive()
        val requestMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !turn.conversationSystemPrompt.isNullOrBlank()) {
                        turn.conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }
                // 群聊公共规则独立于角色人设，不受会话系统提示词覆盖开关限制。
                if (turn.groupSystemPrompt.isNotBlank()) {
                    appendLine()
                    appendLine()
                    appendLine("[群聊提示词]")
                    append(turn.groupSystemPrompt)
                }

                // 可变化的记忆放入用户上下文，不再使 system 随后台记忆整理改变。
                if (turn.stickerPrompt.isNotBlank()) {
                    appendLine()
                    appendLine()
                    append(turn.stickerPrompt)
                }
                if (turn.settings.chatImageGeneration.automatic && turn.settings.mediaGenerationProviders.any { provider ->
                        provider.models.any { model ->
                            model.kind == me.rerere.mediagen.model.MediaKind.IMAGE &&
                                provider.capabilities(model.kind) != null &&
                                (turn.settings.chatImageGeneration.modelId == null || model.id == turn.settings.chatImageGeneration.modelId)
                        }
                    }) {
                    appendLine()
                    appendLine()
                    append(me.rerere.rikkahub.data.model.ChatImageGeneration.INSTRUCTION)
                }
                appendLine()
                appendLine()
                append(ASSISTANT_STATUS_REPLY_INSTRUCTION)
                if (voiceCallActive) {
                    appendLine()
                    appendLine()
                    append(VOICE_CALL_SYSTEM_INSTRUCTION)
                }
                // 工具附加环境提示可能依赖实时配置，同样放到用户上下文。
            }
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            val stickerStore = StickerStore.get(context)
            val imageHistory = messages.limitContext(assistant.contextMessageLimit).map { message ->
                StickerReplyResolver.attachLocalImages(message, stickerStore::isLocalImageForRequest)
            }
            val history = me.rerere.rikkahub.data.ai.transformers.appendMessageDates(imageHistory)
            val dynamicContext = buildString {
                if (assistant.enableMemory) appendLine(buildMemoryPrompt(turn.memories))
                turn.tools.forEach { appendLine(it.systemPrompt(turn.model, messages)) }
            }
            addAll(me.rerere.rikkahub.data.ai.transformers.appendUserContext(history, dynamicContext))
        }.transforms(
            transformers = inputTransformers,
            context = context,
            model = turn.model,
            assistant = assistant,
            settings = turn.settings,
            processingStatus = processingStatus,
            workspaceCwd = turn.workspaceCwd,
        )
        if (me.rerere.rikkahub.BuildConfig.DEBUG) {
            val catalogEntries = turn.stickerPrompt.lineSequence().filter { it.startsWith("{\"group\":") }.toList()
            val finalSystem = requestMessages.filter { it.role == me.rerere.ai.core.MessageRole.SYSTEM }
                .joinToString("\n") { it.toText() }
            Log.d("StickerReply", "[request-ready] conversation=${turn.conversationId} catalogEntries=${catalogEntries.size} entriesInSystem=${catalogEntries.count { finalSystem.contains(it) }} promptChars=${turn.stickerPrompt.length} systemChars=${finalSystem.length}")
        }
        val params = TextGenerationParams(
            model = turn.model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = turn.tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(turn.model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(turn.model.customBodies)
            },
            sessionId = turn.conversationId.toString(),
        )
        val configurationKey = listOf(
            turn.assistant.toString(), turn.model.toString(), turn.conversationSystemPrompt.orEmpty(),
            turn.settings.modeInjections.toString(), turn.settings.lorebooks.toString(),
            turn.settings.displaySetting.userNickname, turn.workspaceCwd.orEmpty(), turn.stickerPrompt,
            turn.settings.chatImageGeneration.toString(),
            voiceCallActive.toString(), turn.groupSystemPrompt,
            turn.settings.mediaGenerationProviders.flatMap { provider ->
                provider.models.filter { it.kind == me.rerere.mediagen.model.MediaKind.IMAGE && provider.capabilities(it.kind) != null }.map { it.id }
            }.toString(),
        ).joinToString("\u0000")
        val stableMessages = renderedPrefixes.stabilize(
            "${turn.conversationId}:${turn.assistant.id}", configurationKey,
            messages.limitContext(assistant.contextMessageLimit), requestMessages,
        )
        // 在完整历史（含工具结果）之后追加，不插入工具调用与结果之间。
        return StepRequest(stableMessages + turn.requestContextMessage, params)
    }
}

/**
 * 取出压缩请求回复里的摘要正文。
 *
 * 指令要求不调用工具，模型仍可能发起调用。这时附带的文本只是"我先看看文件"之类的说明，
 * 当成摘要保存会让之后的请求丢掉全部历史，所以返回 null 交给调用方报错。
 * 压缩请求不经过输出转换器，正文开头的 <think> 思考内容也要在这里剥掉，否则会存进摘要。
 */
internal fun UIMessage.compressionSummaryOrNull(): String? {
    if (getTools().isNotEmpty()) return null
    return listOf(this)
        .transformThinkTags(now = Clock.System.now(), generationFinished = true)
        .single()
        .toText()
        .trim()
}

/**
 * 输出转换只处理这一步新生成的 part：之前步骤的内容已经处理过，再处理一遍会让正则这类改写叠加，
 * 也能让每一步开头的 <think> 都被识别出来。
 */
internal suspend fun UIMessage.transformNewParts(
    newPartsFrom: Int,
    transform: suspend (List<UIMessage>) -> List<UIMessage>,
): UIMessage {
    val transformed = transform(listOf(copy(parts = parts.drop(newPartsFrom)))).single()
    return transformed.copy(parts = parts.take(newPartsFrom) + transformed.parts)
}

/** 标题、建议、记忆整理这类后台请求的参数：只带模型自己的自定义请求头和请求体 */
internal fun backgroundTextGenerationParams(
    model: Model,
    sessionId: Uuid,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
    sessionId = sessionId.toString(),
)

internal fun Settings.retryPolicy() =
    if (networkSetting.enableAutoRetry) RetryPolicy.NETWORK else RetryPolicy.NONE
