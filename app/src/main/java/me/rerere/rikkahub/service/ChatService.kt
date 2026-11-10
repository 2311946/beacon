package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.RetryAttempt
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.ai.ChatTurnContext
import me.rerere.rikkahub.data.ai.AssistantStatusManager
import me.rerere.rikkahub.data.ai.ConversationMetaGenerator
import me.rerere.rikkahub.data.ai.generatedAssistantStatus
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.prompts.buildCompressPrompt
import me.rerere.rikkahub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.hasUnansweredAutonomousWakeup
import me.rerere.rikkahub.data.model.withDeliveredWakeup
import me.rerere.rikkahub.data.model.lastWakeupIdleActivityAt
import me.rerere.rikkahub.data.model.isRealWakeupUserActivity
import kotlinx.datetime.toInstant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ChatImageGeneration
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.voiceCallIdOrNull
import me.rerere.rikkahub.data.model.withVoiceCallMetadata
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.model.withAssistantStatusVisibility
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.model.cancelInterruptedTools
import me.rerere.rikkahub.data.model.deleteMessage
import me.rerere.rikkahub.data.model.editMessage
import me.rerere.rikkahub.data.model.fillModelSnapshots
import me.rerere.rikkahub.data.model.fork
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.getStoredAssistantOf
import me.rerere.rikkahub.data.model.insertContextCheckpoint
import me.rerere.rikkahub.data.model.isAwaitingToolApproval
import me.rerere.rikkahub.data.model.isWakeupTask
import me.rerere.rikkahub.data.model.wakeupTaskStatus
import me.rerere.rikkahub.data.model.withWakeupTaskStatus
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.replaceUserRegexes
import me.rerere.rikkahub.data.model.selectBranch
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withAssistantUpdate
import me.rerere.rikkahub.data.model.withNodeFavorite
import me.rerere.rikkahub.data.model.withSpeaker
import me.rerere.rikkahub.data.model.prepareGroupRegeneration
import me.rerere.rikkahub.data.model.regenerationSpeaker
import me.rerere.rikkahub.data.model.replyNodeForTurn
import me.rerere.rikkahub.data.model.withToolApproval
import me.rerere.rikkahub.data.model.withTranslation
import me.rerere.rikkahub.data.model.withoutConversationFields
import me.rerere.rikkahub.data.model.withoutUnresumableToolCalls
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.data.model.ChatImageGenerationState
import me.rerere.rikkahub.data.model.ChatImageGenerationStatus
import me.rerere.rikkahub.data.model.chatImageGenerationState
import me.rerere.rikkahub.data.model.canManuallyGenerateChatImage
import me.rerere.rikkahub.data.model.shouldAutoGenerateChatImage
import me.rerere.rikkahub.data.model.withChatImageState
import me.rerere.rikkahub.data.model.withChatImageOutput
import kotlinx.coroutines.job
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val assistantStatusManager: AssistantStatusManager,
    private val conversationRepo: ConversationRepository,
    private val requestFactory: ChatRequestFactory,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val translationHandler: TranslationHandler,
    private val metaGenerator: ConversationMetaGenerator,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val folderRepository: FolderRepository,
    private val mediaCreationService: MediaCreationService? = null,
    private val mediaCreationRepository: MediaCreationRepository? = null,
) {
    private val chatImageGenerationJobs = java.util.concurrent.ConcurrentHashMap<Uuid, Job>()
    private val activeVoiceCalls = java.util.concurrent.ConcurrentHashMap<Uuid, String>()
    /** 一轮生成的结果 */
    private sealed interface TurnResult {
        data class Finished(val reason: AgentFinishReason) : TurnResult

        /** 没能完成：错误已经报告给用户，消息队列也已暂停 */
        data object Failed : TurnResult
    }

    private val conversationStore = object : ConversationStore {
        override suspend fun prepareWrite(): (Conversation) -> Conversation {
            // 会话落库即视为开始，此时把助手的配置固定到会话上
            val settings = settingsStore.awaitLoaded()
            return { it.bindConfig(settings).fillModelSnapshots(settings) }
        }

        override suspend fun write(previous: Conversation?, current: Conversation) {
            if (previous == null && !conversationRepo.existsConversationById(current.id)) {
                conversationRepo.insertConversation(current)
            } else {
                conversationRepo.updateConversation(current)
            }
            if (previous != null && previous.messageNodes !== current.messageNodes) {
                withContext(Dispatchers.IO) { deleteUnreferencedFiles(previous, current) }
            }
        }
    }

    private val sessionManager = ConversationSessionManager(
        scope = appScope,
        store = conversationStore,
        createInitialConversation = { id ->
            Conversation.ofId(id, assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id)
        },
        onGenerationFinished = ::onSessionGenerationFinished,
        onSessionRemoved = memoryConsolidationScheduler::onConversationLeft,
    )

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    /** 复用媒体服务的独立 API；持有会话引用直到结果落库，离开页面也能完成。 */
    fun generateImageFromMessage(conversationId: Uuid, messageId: Uuid, prompt: String, automatic: Boolean = false) {
        if (prompt.isBlank()) return
        launchChatImageGeneration(conversationId, messageId, prompt, automatic = automatic)
    }

    private fun launchChatImageGeneration(
        conversationId: Uuid,
        messageId: Uuid,
        prompt: String,
        automatic: Boolean = false,
        resume: Boolean = false,
    ) {
        val service = mediaCreationService ?: return
        val repository = mediaCreationRepository ?: return
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                withLoadedSession(conversationId, createIfMissing = false) { session ->
                    val incarnation = session.incarnation
                    val message = session.state.value.messageNodes.flatMap { it.messages }.find { it.id == messageId }
                        ?: return@withLoadedSession
                    val previous = message.chatImageGenerationState()
                    if (resume) {
                        if (previous?.status?.isActive != true) return@withLoadedSession
                    } else if (automatic) {
                        if (!message.shouldAutoGenerateChatImage(settingsStore.awaitLoaded().chatImageGeneration.automatic)) return@withLoadedSession
                    } else if (!message.canManuallyGenerateChatImage()) return@withLoadedSession

                    var state = if (resume) checkNotNull(previous) else ChatImageGenerationState(
                        prompt = previous?.prompt ?: ChatImageGeneration.prompt(prompt) ?: prompt.trim(),
                        status = ChatImageGenerationStatus.SUBMITTING,
                        recordId = previous?.recordId,
                    )
                    fun isCurrent(): Boolean = session.incarnation == incarnation &&
                        session.state.value.messageNodes.any { node -> node.messages.any { it.id == messageId } }
                    suspend fun persistState() {
                        session.tryUpdate { current ->
                            if (isCurrent()) current.withChatImageState(messageId, state) else null
                        }
                        session.flush()
                    }
                    try {
                        // 在调用付费接口前落库；重启后没有 recordId 的提交不自动重发。
                        persistState()
                        if (!isCurrent()) return@withLoadedSession
                        val existing = state.recordId?.let { repository.getRecord(it) }
                        val record = if (resume) {
                            existing ?: error("上次生图提交已中断或记录已删除，请手动重试")
                        } else if (existing != null) {
                            // 有远端任务 ID 时继续轮询，避免失败重试重复付费。
                            service.retry(existing.id)
                            checkNotNull(repository.getRecord(existing.id)) { "生图记录已删除" }
                        } else {
                            val settings = settingsStore.awaitLoaded()
                            val models = settings.mediaGenerationProviders.flatMap { provider ->
                                provider.models.filter { it.kind == MediaKind.IMAGE && provider.capabilities(it.kind) != null }
                                    .map { provider to it }
                            }
                            val modelId = settings.chatImageGeneration.modelId
                            val selection = if (modelId == null) models.firstOrNull() else models.find { it.second.id == modelId }
                            checkNotNull(selection) {
                                if (modelId == null) "未配置可用的生图模型，请先在媒体服务中添加" else "所选生图模型已不可用，请在聊天生图设置中重新选择"
                            }
                            val mediaSession = repository.getOrCreateEmptySession(
                                MediaCreationDraft(modelId = selection.second.id, prompt = state.prompt),
                            )
                            val recordId = Uuid.random()
                            state = state.copy(recordId = recordId)
                            persistState()
                            if (!isCurrent()) return@withLoadedSession
                            service.submit(MediaCreationSubmission(
                                sessionId = mediaSession.id,
                                provider = selection.first,
                                model = selection.second,
                                prompt = state.prompt,
                                params = MediaCreationParams(count = 1),
                                assets = emptyList(),
                                recordId = recordId,
                            ))
                        }
                        state = state.copy(status = ChatImageGenerationStatus.RUNNING, recordId = record.id, error = null)
                        persistState()
                        val completed = repository.observeRecord(record.id).first { it == null || !it.status.isActive }
                            ?: error("生图记录已删除")
                        if (!isCurrent()) return@withLoadedSession
                        if (completed.status == MediaCreationStatus.SUCCEEDED) {
                            val output = completed.outputs.firstOrNull() ?: error("服务返回成功，但未提供图片")
                            // 聊天持有独立副本，清理媒体历史不会破坏聊天里的图片。
                            val uri = withContext(Dispatchers.IO) {
                                val file = repository.resolve(output.path)
                                check(file.isFile) { "生成图片文件不存在" }
                                filesManager.createChatFilesByContents(listOf(android.net.Uri.fromFile(file))).singleOrNull()
                                    ?: error("无法保存生成图片到聊天附件")
                            }
                            session.tryUpdate { current ->
                                if (isCurrent()) current.withChatImageOutput(messageId, record.id, UIMessagePart.Image(uri.toString())) else null
                            }
                            session.flush()
                        } else {
                            state = state.copy(
                                status = if (completed.status == MediaCreationStatus.CANCELLED) ChatImageGenerationStatus.CANCELLED else ChatImageGenerationStatus.FAILED,
                                error = completed.error ?: if (completed.status == MediaCreationStatus.CANCELLED) "已取消" else "生图失败",
                            )
                            persistState()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        state = state.copy(status = ChatImageGenerationStatus.FAILED, error = e.message ?: "生图失败")
                        persistState()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addError(e, conversationId = conversationId, title = "图片生成失败")
            } finally {
                chatImageGenerationJobs.remove(messageId, coroutineContext.job)
            }
        }
        if (chatImageGenerationJobs.putIfAbsent(messageId, job) == null) job.start() else job.cancel()
    }

    private fun resumeChatImageGeneration(session: ConversationSession) {
        session.state.value.messageNodes.flatMap { it.messages }.forEach { message ->
            message.chatImageGenerationState()?.takeIf { it.status.isActive }?.let { state ->
                launchChatImageGeneration(session.id, message.id, state.prompt, resume = true)
            }
        }
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching { sessionManager.cleanup() }

    private fun onSessionGenerationFinished(session: ConversationSession, cause: Throwable?) {
        // 取消只来自停止（已自行暂停）或删除，这里再暂停会盖掉停止后用户发送时恢复的队列
        if (cause != null && cause !is CancellationException) session.messageQueue.pause()
        if (session.state.value.isAwaitingToolApproval) {
            session.messageQueue.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
        }
        appScope.launch { dispatchNextQueuedMessage(session.id) }
    }

    // 保留 UI/Web 的入口，生命周期和状态查询统一交给 SessionManager。
    fun addConversationReference(conversationId: Uuid) {
        sessionManager.acquire(conversationId)
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessionManager.release(conversationId)
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> =
        sessionManager.getConversationFlow(conversationId)

    /** 确保会话已从数据库加载到内存。群聊页打开时调用，避免只拿到空的初始占位。 */
    suspend fun ensureLoaded(conversationId: Uuid) {
        val session = sessionManager.getOrCreate(conversationId)
        ensureInitialized(session)
    }

    /** 等待会话当前的生成任务完成（队列消息或重新生成），没有任务时立即返回。 */
    suspend fun awaitCurrentJob(conversationId: Uuid) {
        sessionManager.get(conversationId)?.getJob()?.join()
    }

    /** 会话的最新内容：已加载的以内存为准，否则读数据库 */
    suspend fun getConversation(conversationId: Uuid): Conversation? =
        sessionManager.get(conversationId)?.takeIf { it.isInitialized }?.state?.value
            ?: conversationRepo.getConversationById(conversationId)

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> =
        sessionManager.getGenerationJobStateFlow(conversationId)

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> =
        sessionManager.getProcessingStatusFlow(conversationId)

    fun getReplyMessageIdFlow(conversationId: Uuid): StateFlow<Uuid?> =
        sessionManager.getReplyMessageIdFlow(conversationId)

    fun getGenerationStartedAtFlow(conversationId: Uuid): StateFlow<Long?> =
        sessionManager.getGenerationStartedAtFlow(conversationId)

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = sessionManager.getConversationJobs()

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground) return appScope.launch(start = CoroutineStart.LAZY) { block() }

        return appScope.launch(start = CoroutineStart.LAZY) {
            val generationId = Uuid.random()
            val foregroundStarted = ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )
            try {
                block()
            } finally {
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        withLoadedSession(conversationId) { session ->
            resumeChatImageGeneration(session)
            settingsStore.selectAssistant(session.state.value.assistantId)
        }
    }

    private suspend fun ensureInitialized(session: ConversationSession) {
        session.initialize(
            load = { loadConversation(session.id) },
            create = { newConversation(session.id) },
        )
        resumeChatImageGeneration(session)
        val settings = settingsStore.awaitLoaded()
        if (session.state.value.config?.isGroupChat != true &&
            settings.groupChats.any { it.conversationId == session.id }) {
            session.updateMetadata(
                transform = { it.bindConfig(settings) },
                persist = conversationRepo::updateConversationConfig,
            )
        }
        if (session.state.value.config?.isGroupChat == true) syncGroupTitle(session)
    }

    private suspend fun syncGroupTitle(session: ConversationSession) {
        val incarnation = session.incarnation
        val settings = settingsStore.awaitLoaded()
        val group = settings.groupChats.firstOrNull { it.conversationId == session.id } ?: return
        if (session.state.value.title == group.name) return
        session.updateMetadata(
            transform = { latest ->
                val name = settingsStore.settingsFlow.value.groupChats
                    .firstOrNull { it.conversationId == session.id }?.name
                if (latest.config?.isGroupChat == true && name != null) latest.copy(title = name) else latest
            },
            persist = { conversationRepo.updateTitle(session.id, it.title) },
            expectedIncarnation = incarnation,
        )
    }

    /** 持有会话引用并确保已加载后执行 [block]；[createIfMissing] 为 false 时数据库里没有就抛 NotFoundException */
    private suspend fun <T> withLoadedSession(
        conversationId: Uuid,
        createIfMissing: Boolean = true,
        block: suspend (ConversationSession) -> T,
    ): T = sessionManager.withSession(conversationId) { session ->
        session.initialize(
            load = { loadConversation(conversationId) },
            create = {
                if (!createIfMissing) throw NotFoundException("Conversation not found")
                newConversation(conversationId)
            },
        )
        block(session)
    }

    // 新建对话, 并添加预设消息
    private suspend fun newConversation(conversationId: Uuid): Conversation {
        val assistant = settingsStore.awaitLoaded().getCurrentAssistant()
        return Conversation.ofId(
            id = conversationId,
            assistantId = assistant.id,
            newConversation = true
        ).updateCurrentMessages(assistant.presetMessages)
    }

    // 引入会话配置之前创建的会话没有固定配置，加载时按助手当前的值补上，之后不再随助手变化。
    private suspend fun loadConversation(conversationId: Uuid): Conversation? {
        val conversation = conversationRepo.getConversationById(conversationId) ?: return null
        // settingsFlow 在启动初期还是占位值，固定配置必须基于真实设置
        val bound = conversation.bindConfig(settingsStore.awaitLoaded())
        if (bound !== conversation) conversationRepo.updateConversationConfig(bound)
        return bound
    }

    /**
     * 加载会话后在最新状态上修改并落库。
     * 删除消息或切换分支也可能解除工具审批阻塞，落库后重新检查队列。
     */
    private suspend fun mutate(conversationId: Uuid, transform: (Conversation) -> Conversation): Conversation =
        withLoadedSession(conversationId) { session ->
            session.update(transform)
            session.flush().also { dispatchNextQueuedMessage(conversationId) }
        }

    // 只删上次落库时引用、这次落库不再引用的文件；只在内存里出现过的文件宁可漏删也不误删
    private fun deleteUnreferencedFiles(previous: Conversation, current: Conversation) {
        val session = sessionManager.get(current.id)
        val queuedFiles = (session?.messageQueue?.state?.value?.messages.orEmpty() +
            listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls().map { it.toUri() }
        val retainedFiles = (current.files + queuedFiles).toSet()
        val deletedFiles = previous.files.filter { it !in retainedFiles }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "deleteUnreferencedFiles: $deletedFiles")
        }
    }

    // ---- 发送消息 ----

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        sessionManager.getMessageQueueFlow(conversationId)

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessionManager.get(conversationId)?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessionManager.get(conversationId)?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessionManager.get(conversationId)?.messageQueue?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessionManager.snapshot()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessionManager.get(conversationId)?.messageQueue?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(content, answer)
            dispatchNextQueuedMessage(conversationId)
        }
    }

    internal fun isBusyForAutonomousWakeup(conversationId: Uuid): Boolean {
        val session = sessionManager.get(conversationId) ?: return false
        return synchronized(session) {
            session.getJob() != null || session.state.value.isAwaitingToolApproval ||
                session.messageQueue.state.value.messages.isNotEmpty()
        }
    }

    /** 决策已经生成的联系正文直接作为助手消息落库，不再进行第二次模型请求。 */
    internal suspend fun deliverWakeupMessage(
        conversationId: Uuid,
        assistantId: Uuid,
        expectedLastMessageId: Uuid?,
        text: String,
        metadata: kotlinx.serialization.json.JsonObject,
    ): String? = withLoadedSession(conversationId, createIfMissing = false) { session ->
        val settings = settingsStore.awaitLoaded()
        if (text.isBlank() || settings.groupChats.any { it.conversationId == conversationId }) return@withLoadedSession null
        val message = UIMessage.assistant(text).copy(
            parts = listOf(UIMessagePart.Text(text, metadata)),
            modelId = settings.getChatModelOf(session.state.value)?.id,
        )
        val accepted = synchronized(session) {
            if (session.getJob() != null || session.state.value.isAwaitingToolApproval ||
                session.messageQueue.state.value.messages.isNotEmpty()) {
                false
            } else {
                session.tryUpdate { it.withDeliveredWakeup(message, assistantId, expectedLastMessageId) } != null
            }
        }
        if (!accepted) return@withLoadedSession null
        withContext(NonCancellable) { session.flush() }
        // 已经落库的消息不应因 UI 完成事件的背压/调用方取消而被报告为未交付。
        appScope.launch { _generationDoneFlow.emit(conversationId) }
        text
    }

    /** 后台唤醒不得排到新用户消息后面；检查和入队在同一会话锁内完成。 */
    fun sendWakeupMessage(
        conversationId: Uuid,
        expectedLastMessageId: Uuid?,
        content: List<UIMessagePart>,
    ): Deferred<String?>? {
        val session = sessionManager.get(conversationId) ?: return null
        synchronized(session) {
            if (session.getJob() != null || session.state.value.isAwaitingToolApproval ||
                session.messageQueue.state.value.messages.isNotEmpty() ||
                session.state.value.currentMessages.lastOrNull()?.id != expectedLastMessageId ||
                session.state.value.currentMessages.hasUnansweredAutonomousWakeup()) return null
            // 复用队列已有的单项结果，不能等待“当前任务”再读取可能属于下一轮的末条消息。
            val reply = CompletableDeferred<String?>()
            session.messageQueue.resume()
            session.messageQueue.enqueue(content, answer = true, reply = reply)
            dispatchNextQueuedMessage(conversationId)
            return reply
        }
    }

    /** 仅保留进程内活动通话状态，挂断不改任何持久化角色提示词。 */
    fun setActiveVoiceCall(conversationId: Uuid, callId: String?) {
        if (callId == null) activeVoiceCalls.remove(conversationId) else activeVoiceCalls[conversationId] = callId
    }

    fun enqueueVoiceMessage(conversationId: Uuid, text: String, callId: String? = null): Deferred<String?> {
        val session = sessionManager.getOrCreate(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(!session.state.value.isAwaitingToolApproval) {
                context.getString(R.string.chat_page_voice_tools_before_resume)
            }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            val message = UIMessage.user(text)
            val parts = callId?.let { message.withVoiceCallMetadata(it).parts } ?: message.parts
            session.messageQueue.enqueue(parts, reply = reply)
            dispatchNextQueuedMessage(conversationId)
        }
        return reply
    }

    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessionManager.get(conversationId) ?: return null
        synchronized(session) {
            // A pending tool approval is still part of the current turn.
            if (session.getJob() != null || session.state.value.isAwaitingToolApproval) return null
            val next = session.messageQueue.takeNext() ?: return null
            session.submittingMessage = next
            return sendQueuedMessage(session, next)
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        // 新消息开始初始化前就清掉旧回复，初始化失败或任务启动前取消也不能留下旧预览。
        session.replyMessageId.value = null
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = queued.answer,
        ) {
            try {
                ensureInitialized(session)
                finishInterruptedPendingTools(session)

                val assistant = settingsStore.awaitLoaded().getStoredAssistantOf(session.state.value)
                val processedContent = queued.parts.replaceUserRegexes(assistant)

                // 添加消息到列表
                val previousIds = session.state.value.currentMessages.mapTo(HashSet()) { it.id }
                session.update {
                    it.copy(
                        messageNodes = it.messageNodes + UIMessage(
                            role = MessageRole.USER,
                            parts = processedContent,
                        ).toMessageNode(),
                    )
                }
                session.flush()
                session.submittingMessage = null

                // 开始补全
                val result = if (queued.answer) runTurn(
                    session,
                    // Worker 会在成功回复后发角色通知，这里只负责生成和进度清理。
                    notifyCompletion = !hasDedicatedWakeupNotification(queued.parts),
                    voiceCallId = UIMessage(role = MessageRole.USER, parts = queued.parts).voiceCallIdOrNull(),
                ) else null

                // Re-arm idle checks after a real user turn, including after replying to a wakeup.
                val latestMessages = session.state.value.currentMessages
                val wakeupSettings = settingsStore.awaitLoaded().autonomousWakeup
                if (UIMessage(role = MessageRole.USER, parts = queued.parts).isRealWakeupUserActivity() &&
                    wakeupSettings.enabled && session.state.value.config?.isGroupChat != true &&
                    session.state.value.assistantId in wakeupSettings.assistantIds &&
                    !latestMessages.hasUnansweredAutonomousWakeup()) {
                    val lastUserAt = latestMessages.lastWakeupIdleActivityAt()
                    if (lastUserAt != null) {
                        val elapsed = kotlin.time.Clock.System.now() - lastUserAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault())
                        runCatching {
                            ProactiveMessageScheduler.scheduleAfterUserActivity(
                                context, session.state.value.assistantId,
                                (wakeupSettings.intervalMinutes * 60_000L - elapsed.inWholeMilliseconds).coerceAtLeast(1_000),
                            )
                        }.onFailure { Log.e(TAG, "Failed to schedule wakeup idle deadline", it) }
                    }
                }

                queued.reply?.completeWith(runCatching {
                    check(result != TurnResult.Failed) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check((result as? TurnResult.Finished)?.reason !is AgentFinishReason.AwaitingApproval) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    session.state.value.currentMessages
                        .filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // Wakeup also observes its own result, but keeps the existing autoplay behavior.
                if (queued.reply == null || hasDedicatedWakeupNotification(queued.parts)) {
                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                queued.reply?.completeExceptionally(e)
                e.printStackTrace()
                reportFailure(session, e, R.string.error_title_send_message)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) queued.reply?.completeExceptionally(cause)
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        session.setJob(job)
        return job
    }

    // 发送、重新生成和审批失败时报告错误并暂停队列，排队的消息等用户处理完再继续
    private fun reportFailure(session: ConversationSession, error: Exception, @StringRes title: Int) {
        if (error is CancellationException) throw error
        session.messageQueue.pause()
        addError(error, session.id, title = context.getString(title))
    }

    // ---- 重新生成消息 ----

    /** 群聊用户消息只裁剪并保存历史，具体回复交由完整群聊调度器执行。 */
    internal suspend fun prepareGroupRegeneration(conversationId: Uuid, messageId: Uuid) {
        withLoadedSession(conversationId, createIfMissing = false) { session ->
            stopGeneration(conversationId)
            withContext(NonCancellable) {
                session.update { it.prepareGroupRegeneration(messageId) }
                session.replyMessageId.value = null
                session.flush()
            }
        }
    }

    /**
     * 重新生成用户消息时去掉它之后的节点再回复；重新生成助手消息时在它的节点上新开一个分支。
     * 群聊用户消息由 GroupChatService 调用完整的群聊回复流程，不走此单角色入口。
     *
     * @param speakerAssistantId 群聊里指定由哪个成员来说这条（会同时写回 speakerMap），单聊传 null 用会话自己的助手。
     */
    fun regenerateAtMessage(conversationId: Uuid, messageId: Uuid, speakerAssistantId: Uuid? = null) {
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            val previousJob = session.getJob()
            val job = launchGenerationJob(conversationId = conversationId) {
                try {
                    previousJob?.join()
                    session.replyMessageId.value = null
                    ensureInitialized(session)
                    val snapshot = session.state.value
                    val node = snapshot.getMessageNodeByMessageId(messageId)
                        ?: return@launchGenerationJob
                    val lockedSpeakerId = snapshot.regenerationSpeaker(messageId, speakerAssistantId)
                    if (lockedSpeakerId != null) {
                        check(settingsStore.awaitLoaded().assistants.any { it.id == lockedSpeakerId }) {
                            "原消息角色已被删除，无法重新生成；不会自动切换为其他角色"
                        }
                    }

                    if (node.role == MessageRole.USER) {
                        session.tryUpdate { conversation ->
                            val index = conversation.messageNodes.indexOfFirst { it.id == node.id }
                            // 查找之后这条消息被删掉了
                            if (index == -1) return@tryUpdate null
                            conversation.copy(messageNodes = conversation.messageNodes.take(index + 1))
                        } ?: return@launchGenerationJob
                        session.flush()
                        runTurn(session, speakerAssistantId = lockedSpeakerId)
                    } else {
                        runTurn(
                            session,
                            regenerateNodeId = node.id,
                            speakerAssistantId = lockedSpeakerId,
                        )
                    }

                } catch (e: Exception) {
                    reportFailure(session, e, R.string.error_title_regenerate_message)
                } finally {
                    withContext(NonCancellable) { _generationDoneFlow.emit(conversationId) }
                }
            }

            session.setJob(job)
        }
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            val previousJob = session.getJob()

            val hasOtherPendingTools = session.state.value.currentMessages.any { message ->
                message.getTools().any { it.isPending && it.toolCallId != toolCallId }
            }

            val job = launchGenerationJob(
                conversationId = conversationId,
                keepAliveInBackground = !hasOtherPendingTools,
            ) {
                try {
                    afterPreviousGeneration(previousJob) {
                        ensureInitialized(session)
                        val newApprovalState = when {
                            answer != null -> ToolApprovalState.Answered(answer)
                            approved -> ToolApprovalState.Approved
                            else -> ToolApprovalState.Denied(reason)
                        }

                        val updated = session.tryUpdate { it.withToolApproval(toolCallId, newApprovalState) }
                            ?: return@afterPreviousGeneration
                        session.flush()

                        // Only continue generation when all pending tools are handled
                        if (!updated.isAwaitingToolApproval) {
                            runTurn(session)
                        }

                        _generationDoneFlow.emit(conversationId)
                    }
                } catch (e: Exception) {
                    reportFailure(session, e, R.string.error_title_tool_approval)
                }
            }

            session.setJob(job, cancelPrevious = false)
        }
    }

    // ---- 生成回复 ----

    /**
     * 生成一轮回复：请求模型、执行工具，直到模型不再调用工具、有工具需要审批或达到步数上限。
     *
     * 默认接着当前分支的末尾回复；[regenerateNodeId] 不为空时只发送这个节点之前的消息，回复作为它的新分支。
     * 发送的最后一条是助手消息时从它继续（比如审批之后），回复写回那条消息。
     * 失败时报告错误、暂停消息队列并返回 [TurnResult.Failed]；取消时直接抛出。
     */
    private suspend fun runTurn(
        session: ConversationSession,
        regenerateNodeId: Uuid? = null,
        speakerAssistantId: Uuid? = null,
        runPostTurnTasks: Boolean = true,
        notifyCompletion: Boolean = true,
        voiceCallId: String? = null,
    ): TurnResult {
        val conversationId = session.id
        // 先清理上一轮状态；确定历史后，仅为真正的续写恢复消息 ID。
        session.replyMessageId.value = null
        // 生成开始前失败时通知只用来取消 Live Update，用不到发送者名称
        var senderName = ""
        var wakeupTaskId: Uuid? = null
        var taskStatus = "interrupted"
        try {
            val settings = settingsStore.awaitLoaded()
            // 清掉上一轮的建议；没法继续执行的工具调用发给模型会被拒绝，先去掉。
            // 重新生成时只清理要发给模型的节点，被重新生成的节点保持原样，旧回复留作分支
            val conversation = session.update { latest ->
                val untilNode = regenerateNodeId
                    ?.let { nodeId -> latest.messageNodes.indexOfFirst { it.id == nodeId } }
                    ?.takeIf { it != -1 }
                    ?: latest.messageNodes.size
                latest.copy(chatSuggestions = emptyList()).withoutUnresumableToolCalls(untilNode)
            }

            val nodes = conversation.messageNodes
            val history = if (regenerateNodeId != null) {
                val index = nodes.indexOfFirst { it.id == regenerateNodeId }
                check(index != -1) { "Message node $regenerateNodeId not found" }
                conversation.currentMessages.take(index)
            } else {
                conversation.currentMessages
            }
            // 审批续跑仍关联同一条任务事件，不把后续普通用户消息归到旧任务。
            wakeupTaskId = history.lastOrNull { it.role == MessageRole.USER }
                ?.takeIf { it.isWakeupTask() }?.id
            wakeupTaskId?.let { taskId -> session.update { it.withWakeupTaskStatus(taskId, "running") } }
            // 群聊：对齐华灯 buildHistoryWithNames 方案——
            // 每个角色生成时看到完整群聊上下文，但能分清谁是谁：
            // - 用户消息 → USER: [用户名]: 内容（让角色知道跟自己说话的人叫什么）
            // - 自己之前的回复 → ASSISTANT: 内容（不变，模型知道是自己说的）
            // - 其他成员的回复 → USER: [角色名]: 内容（转 USER 加名字标签）
            // 这样每个角色都能看到完整群聊，分清用户、自己和其他成员。
            // 只影响发给模型的上下文，不改 session 状态。
            val effectiveHistory = if (speakerAssistantId != null) {
                val speakerMap = conversation.config?.speakerMap ?: emptyMap()
                val userName = settings.displaySetting.userNickname.ifBlank { "用户" }
                history.map { msg ->
                    when {
                        // 用户消息：加名字前缀，保持 USER 角色
                        msg.role == MessageRole.USER -> msg.copy(
                            parts = listOf(UIMessagePart.Text("[$userName]: ${msg.toText()}")) +
                                msg.parts.filterNot { it is UIMessagePart.Text },
                        )
                        // ASSISTANT 消息：看是谁说的
                        msg.role == MessageRole.ASSISTANT -> {
                            val msgSpeakerId = speakerMap[msg.id]
                            when {
                                // 自己的回复或没记录发言人：保持 ASSISTANT 不变
                                msgSpeakerId == null || msgSpeakerId == speakerAssistantId -> msg
                                // 其他成员的回复：转成 USER + [角色名] 前缀
                                else -> {
                                    val name = settings.assistants
                                        .firstOrNull { it.id == msgSpeakerId }
                                        ?.name?.ifBlank { null } ?: "成员"
                                    msg.copy(
                                        role = MessageRole.USER,
                                        parts = listOf(UIMessagePart.Text("[$name]: ${msg.toText()}")),
                                    )
                                }
                            }
                        }
                        // 其他角色的消息（SYSTEM 等）：原样保留
                        else -> msg
                    }
                }
            } else history

            // 群聊增强：场景说明 + 单聊上下文注入
            // 1. 场景说明（始终注入）：告诉角色这是群聊，列出成员；回复风格由群聊 SYSTEM 提示词控制
            // 2. 单聊上下文（可选）：注入该角色与用户的最近私聊消息
            val groupChat = if (speakerAssistantId != null) {
                settings.groupChats.firstOrNull { it.conversationId == conversationId }
            } else null
            val groupSystemPrompt = if (speakerAssistantId != null) {
                (groupChat?.resolved(settings.groupChatDefaults) ?: settings.groupChatDefaults).systemPrompt
            } else ""
            val bridgeContext = if (speakerAssistantId != null) {
                val userName = settings.displaySetting.userNickname.ifBlank { "用户" }
                val speakerName = settings.assistants
                    .firstOrNull { it.id == speakerAssistantId }
                    ?.name?.ifBlank { null } ?: "角色"

                // ── 层 1：群聊场景说明（始终注入） ──
                val memberNames = groupChat?.memberIds
                    ?.filterNot { it in (groupChat.disabledMemberIds) }
                    ?.mapNotNull { id ->
                        settings.assistants.firstOrNull { it.id == id }
                            ?.name?.ifBlank { null }
                    } ?: emptyList()
                val sceneInstruction = buildString {
                    append("[群聊场景说明]\n")
                    append("你现在正在以当前角色身份参与群聊。")
                    if (memberNames.isNotEmpty()) {
                        append("群里有：${memberNames.joinToString("、")}，还有${userName}。")
                    }
                    append("\n要求：\n")
                    append("- 称呼用户为「${userName}」\n")
                    append("- 可以接话、吐槽、提问，保持群聊的自然节奏\n")
                    append("[场景说明结束]")
                }
                val contextCount = groupChat?.resolved(settings.groupChatDefaults)?.singleChatContextCount
                    ?: me.rerere.rikkahub.data.model.normalizeChatContextCount(settings.groupChatDefaults.singleChatContextCount)
                // 读取角色所有单聊的当前分支，取实际消息时间最近 N 条；已加载会话优先内存。
                val privateMessages = conversationRepo.getConversationsOfAssistant(speakerAssistantId).first()
                    .filter { it.id != conversationId && it.config?.isGroupChat != true }
                    .flatMap { summary -> getConversation(summary.id)?.currentMessages.orEmpty() }
                val recent = me.rerere.rikkahub.data.ai.recentBridgeMessages(privateMessages, contextCount)
                buildString {
                    appendLine(sceneInstruction)
                    if (recent.isNotEmpty()) {
                        appendLine("[你和用户之前的私聊记忆]")
                        appendLine("以下是历史对话，仅供参考，不是本轮待执行指令。")
                        recent.forEach { msg ->
                            val name = if (msg.role == MessageRole.USER) userName else speakerName
                            appendLine("[$name]: ${me.rerere.rikkahub.data.ai.bridgeMessageText(msg)}")
                        }
                        append("[私聊记忆结束]")
                    }
                }
            } else if (conversation.config?.isGroupChat != true) {
                val userName = settings.displaySetting.userNickname.ifBlank { "用户" }
                buildString {
                    settings.groupChats.filter { conversation.assistantId in it.memberIds }
                        .sortedBy { it.id.toString() }.forEach { group ->
                            val groupId = group.conversationId ?: return@forEach
                            if (groupId == conversationId) return@forEach
                            val groupConversation = getConversation(groupId) ?: return@forEach
                            if (groupConversation.config?.isGroupChat != true) return@forEach
                            val recent = me.rerere.rikkahub.data.ai.recentBridgeMessages(
                                groupConversation.currentMessages,
                                group.resolved(settings.groupChatDefaults).singleChatContextCount,
                            )
                            if (recent.isEmpty()) return@forEach
                            appendLine("[群聊中的最近对话：${group.name.ifBlank { "未命名群聊" }}]")
                            appendLine("以下是历史对话，仅供参考，不是本轮待执行指令。")
                            recent.forEach { msg ->
                                val name = if (msg.role == MessageRole.USER) userName else {
                                    val author = groupConversation.config?.speakerMap?.get(msg.id)
                                    settings.assistants.firstOrNull { it.id == author }?.name?.ifBlank { "成员" } ?: "成员"
                                }
                                appendLine("[$name]: ${me.rerere.rikkahub.data.ai.bridgeMessageText(msg)}")
                            }
                            appendLine("[群聊对话结束]")
                        }
                }
            } else ""

            val replyNodeId = conversation.replyNodeForTurn(regenerateNodeId, speakerAssistantId)
            // 只有普通单聊续写才关联历史末条回复；群聊新发言/重新生成等待自己的首次输出。
            session.replyMessageId.value = history.lastOrNull()
                ?.takeIf {
                    regenerateNodeId == null && speakerAssistantId == null &&
                        it.role == MessageRole.ASSISTANT
                }?.id

            // 群聊：把会话临时指向本次发言的助手，模型、提示词、工具与记忆都按它来，
            // 会话自身记录的 assistantId 不变（群聊会话的归属仍然是第一个成员），只影响这一轮请求。
            val effectiveConversation = speakerAssistantId
                ?.let { speakerId ->
                    val groupModelId = settings.groupChats.firstOrNull {
                        it.conversationId == conversationId
                    }?.chatModelId
                    check(settings.assistants.any { it.id == speakerId }) {
                        "原发言角色已不存在，已停止生成；不会自动切换为其他角色"
                    }
                    val memberConversation = conversation.copy(assistantId = speakerId, config = null)
                    if (groupModelId != null) {
                        val bound = memberConversation.bindConfig(settings)
                        bound.copy(config = bound.config?.copy(chatModelId = groupModelId))
                    } else memberConversation
                }
                ?: conversation
            // 来源随本轮固定：挂断后晚到的回复仍属于原通话，不能串到下一次通话。
            val replyCallId = voiceCallId ?: history.lastOrNull()?.voiceCallIdOrNull()
            val turn = requestFactory.createContext(
                settings,
                effectiveConversation,
                chatContext = bridgeContext,
                groupSystemPrompt = groupSystemPrompt,
                isVoiceCallActive = {
                    replyCallId != null && activeVoiceCalls[conversationId] == replyCallId
                },
            )
            senderName = chatNotificationSenderName(
                turn.assistant,
                context.getString(R.string.assistant_page_default_assistant),
            )
            warnIfToolsUnavailable(turn, conversationId)

            val (request, hooks) = requestFactory.createAgent(turn, effectiveHistory, session.processingStatus)
            var finishReason: AgentFinishReason? = null
            try {
                val generationRequest = request.copy(
                    continueLastAssistantMessage = regenerateNodeId == null && speakerAssistantId == null,
                )
                runAgent(generationRequest, hooks).flowOn(Dispatchers.IO).collect { event ->
                    when (event) {
                        is AgentEvent.MessageUpdated -> {
                            // 生成过程中只有网络重试的提示会留着，收到新内容说明重试已经成功
                            session.processingStatus.value = null
                            // 每次流式快照都合并，否则后续 chunk 会覆盖首个 chunk 的标记。
                            val replyMessage = replyCallId?.let { event.message.withVoiceCallMetadata(it) }
                                ?: event.message
                            session.update {
                                val updated = it.upsertMessage(replyNodeId, replyMessage)
                                if (speakerAssistantId != null) {
                                    updated.withSpeaker(event.message.id, speakerAssistantId)
                                } else {
                                    updated
                                }
                            }
                            session.replyMessageId.value = event.message.id
                            // 通知等边缘副作用由 ChatNotificationManager 消费；
                            // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, event.message, senderName)
                            )
                        }

                        is AgentEvent.Retrying -> {
                            logRetry(event.attempt)
                            session.processingStatus.value = retryStatus(event.attempt)
                        }

                        is AgentEvent.Finished -> {
                            Log.i(TAG, "runTurn: finished (${event.reason})")
                            finishReason = event.reason
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    session.processingStatus.value = null
                    // 可能被取消了，或者意外结束，兜底保存
                    val finished = session.finishGeneration()
                    // 生成结束：取消 Live Update 通知，后台时发送完成通知
                    appEventBus.emit(
                        AppEvent.ChatGenerationEnded(
                            conversationId = conversationId,
                            senderName = senderName,
                            // null 仍会取消进度通知，但不再发送第二条完成通知。
                            contentPreview = if (notifyCompletion) {
                                finished.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
                            } else null,
                        )
                    )
                }
            }

            val reason = checkNotNull(finishReason)
            // 状态来自本轮最终助手消息的隐藏 metadata；只有完整成功才写缓存，失败/取消/审批挂起均保留旧值。
            if (reason == AgentFinishReason.Completed) {
                session.state.value.messageNodes.flatMap { it.messages }
                    .find { it.id == session.replyMessageId.value }
                    ?.generatedAssistantStatus()
                    ?.let { status -> assistantStatusManager.storeGeneratedStatus(turn.assistant.id, status) }
            }
            // 等待审批时这一轮还没结束，标题、建议和记忆整理等审批之后再做
            if (reason !is AgentFinishReason.AwaitingApproval && runPostTurnTasks) {
                if (session.state.value.config?.isGroupChat != true) {
                    sessionManager.launchWithSession(conversationId) { generateTitle(conversationId) }
                    sessionManager.launchWithSession(conversationId) { generateSuggestion(conversationId) }
                }
                if (turn.assistant.enableMemory) memoryConsolidationScheduler.onTurnFinished(session.state.value)
            }
            if (reason == AgentFinishReason.Completed && runPostTurnTasks && mediaCreationService != null && mediaCreationRepository != null) {
                val assistantMessage = session.state.value.messageNodes.flatMap { it.messages }
                    .find { it.id == session.replyMessageId.value }
                val imagePrompt = assistantMessage?.toText()?.let(ChatImageGeneration::prompt)
                if (assistantMessage != null && imagePrompt != null && assistantMessage.shouldAutoGenerateChatImage(settingsStore.settingsFlow.value.chatImageGeneration.automatic)) {
                    generateImageFromMessage(conversationId, assistantMessage.id, imagePrompt, automatic = true)
                }
            }
            if (wakeupTaskId != null) {
                taskStatus = if (reason is AgentFinishReason.AwaitingApproval) {
                    "awaiting_approval"
                } else {
                    me.rerere.rikkahub.data.model.wakeupTaskStatus(
                        messages = session.state.value.currentMessages.dropWhile { it.id != wakeupTaskId }.drop(1),
                        completed = reason == AgentFinishReason.Completed,
                        awaitingApproval = false,
                        startedAt = history.first { it.id == wakeupTaskId }.createdAt,
                    )
                }
            }
            return TurnResult.Finished(reason)
        } catch (e: CancellationException) {
            taskStatus = "interrupted"
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            throw e
        } catch (e: InvalidMcpServerNamesException) {
            taskStatus = "failed"
            session.messageQueue.pause()
            addError(
                error = IllegalStateException(
                    context.getString(R.string.error_mcp_invalid_server_name, e.names.joinToString(", "))
                ),
                conversationId = conversationId,
            )
            return TurnResult.Failed
        } catch (e: Exception) {
            taskStatus = "failed"
            // 兜底取消 Live Update 通知（生成开始前失败时上面的 finally 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            session.messageQueue.pause()
            e.printStackTrace()
            addError(e, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "runTurn: $e")
            Logging.log(TAG, e.stackTraceToString())
            return TurnResult.Failed
        } finally {
            wakeupTaskId?.let { taskId ->
                withContext(NonCancellable) {
                    val result = session.state.value.currentMessages
                        .dropWhile { it.id != taskId }.drop(1)
                        .filter { it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                    session.update { it.withWakeupTaskStatus(taskId, taskStatus, result) }
                    session.flush()
                }
            }
        }
    }

    /**
     * 群聊：让指定的助手接着当前历史说一句。
     *
     * 与 [sendMessage] 的区别：不再往消息队列里塞用户消息，只生成一条助手回复；
     * 生成时把会话临时指向 [speakerAssistantId]，因此用的是该助手自己的模型、
     * 提示词、工具与记忆。这条消息属于谁记在 ConversationConfig.speakerMap 上。
     *
     * @param runPostTurnTasks 是否执行回合后处理；群聊跳过标题和建议的模型生成。
     * @return 是否生成成功。失败的错误已经通过 [addError] 上报，调用方不必重复提示。
     */
    suspend fun generateForSpeaker(
        conversationId: Uuid,
        speakerAssistantId: Uuid,
        runPostTurnTasks: Boolean = true,
    ): Boolean {
        val session = sessionManager.getOrCreate(conversationId)
        val result = CompletableDeferred<Boolean>()
        val job = synchronized(session) {
            val previous = session.getJob()
            val next = launchGenerationJob(conversationId) {
                try {
                    afterPreviousGeneration(previous) {
                        ensureInitialized(session)
                        session.replyMessageId.value = null
                        val turn = runTurn(
                            session = session,
                            speakerAssistantId = speakerAssistantId,
                            runPostTurnTasks = runPostTurnTasks,
                        )
                        result.complete(turn != TurnResult.Failed &&
                            (turn as? TurnResult.Finished)?.reason !is AgentFinishReason.AwaitingApproval)
                    }
                } catch (e: Exception) {
                    result.completeExceptionally(e)
                    reportFailure(session, e, R.string.error_title_send_message)
                }
            }
            next.invokeOnCompletion { cause ->
                if (cause != null) result.completeExceptionally(cause)
            }
            session.setJob(next, cancelPrevious = false)
            next
        }
        return try {
            result.await().also { job.join() }
        } catch (e: CancellationException) {
            // 只由后台轮次显式停止时取消，页面退出不再拥有这个调用者。
            job.cancel()
            withContext(NonCancellable) { job.join() }
            throw e
        }
    }

    // 模型不支持工具调用时，搜索和 MCP 工具都不会生效，提醒一下
    private fun warnIfToolsUnavailable(turn: ChatTurnContext, conversationId: Uuid) {
        if (ModelAbility.TOOL in turn.model.abilities) return
        if (shouldUseExternalWebSearch(turn.assistant, turn.model) ||
            mcpManager.getAllAvailableTools(turn.assistant).isNotEmpty()
        ) {
            addError(
                IllegalStateException(context.getString(R.string.tools_warning)),
                conversationId,
                title = context.getString(R.string.error_title_tool_unavailable)
            )
        }
    }

    private fun logRetry(attempt: RetryAttempt) {
        Log.w(
            TAG,
            "Provider connection failed, retrying in ${attempt.delay} (${attempt.retry}/${attempt.maxRetries})",
            attempt.cause,
        )
    }

    private fun retryStatus(attempt: RetryAttempt): String {
        val reason = when (attempt.cause) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(
            R.string.chat_generation_network_retrying,
            context.getString(reason),
            attempt.retry,
            attempt.maxRetries,
        )
    }

    private suspend fun finishInterruptedPendingTools(session: ConversationSession) {
        session.tryUpdate { it.cancelInterruptedTools() }
        session.flush()
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        force: Boolean = false
    ): Unit = withContext(Dispatchers.IO) {
        withLoadedSession<Unit>(conversationId) { session ->
            val incarnation = session.incarnation
            val conversation = session.state.value
            if (conversation.config?.isGroupChat == true) {
                syncGroupTitle(session)
                return@withLoadedSession
            }
            if (!force && conversation.title.isNotBlank()) return@withLoadedSession

            val settings = settingsStore.awaitLoaded()
            // 关掉自动生成后只跳过对话结束时的自动调用，手动重新生成照常执行
            if (!force && !settings.enableAutoTitle) return@withLoadedSession

            runCatching {
                val title = metaGenerator.generateTitle(settings, conversation)
                // 只写标题这一列：这时可能已经在生成下一轮，不能把还没生成完的内容一起落库。
                // 生成期间会话被删除的话不写入
                session.updateMetadata(
                    transform = { it.copy(title = title) },
                    persist = { conversationRepo.updateTitle(conversationId, title) },
                    expectedIncarnation = incarnation,
                )
            }.onFailure {
                it.printStackTrace()
                addError(
                    error = it,
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_generate_title),
                    solution = ChatErrorSolution.CheckFastModelSettings,
                )
            }
        }
    }

    // ---- 生成建议 ----

    private suspend fun generateSuggestion(conversationId: Uuid): Unit = withContext(Dispatchers.IO) {
        sessionManager.withSession<Unit>(conversationId) { session ->
            runCatching {
                val settings = settingsStore.awaitLoaded()
                if (!settings.enableSuggestion) return@runCatching

                // 上一轮的建议在这一轮开始时已经清掉了
                ensureInitialized(session)
                if (session.state.value.config?.isGroupChat == true) return@runCatching
                val incarnation = session.incarnation
                val suggestions = metaGenerator.generateSuggestions(settings, session.state.value)
                    ?: return@runCatching

                // 只写建议这一列，理由同标题
                session.updateMetadata(
                    transform = { it.copy(chatSuggestions = suggestions) },
                    persist = { conversationRepo.updateChatSuggestions(conversationId, suggestions) },
                    expectedIncarnation = incarnation,
                )
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    // ---- 压缩对话历史 ----

    /**
     * 把上一个检查点之后的消息压缩成摘要检查点。
     *
     * [keepRecentMessages] 为 0 时压缩全部消息，请求和上一轮对话前缀一致，缓存命中最好；
     * 大于 0 时最近的这些消息不参与压缩、留在检查点之后，对话已经超出模型上下文、整段发不出去时用来缩小压缩请求。
     */
    suspend fun compressConversation(
        conversationId: Uuid,
        additionalPrompt: String,
        keepRecentMessages: Int = 0,
    ): Result<Unit> = runCatching {
        withLoadedSession<Unit>(conversationId) { session ->
            // 生成中的回复还没写完，压缩不到它，检查点也不能插到它前面
            check(!session.isGenerating) { context.getString(R.string.chat_page_compress_blocked_generating) }

            val conversation = session.state.value
            val nodes = conversation.messageNodes
            val checkpointIndex = nodes.indexOfLast { it.currentMessage.isContextCheckpoint }
            // 压缩范围是 (checkpointIndex, endIndex)，之后的节点原样保留
            val endIndex = nodes.size - keepRecentMessages.coerceAtLeast(0)
            // 上一个检查点之后没有可压缩的消息
            if (endIndex <= checkpointIndex + 1) {
                throw IllegalStateException(
                    context.getString(
                        if (keepRecentMessages > 0) {
                            R.string.chat_page_compress_not_enough_messages
                        } else {
                            R.string.chat_page_compress_nothing_to_compress
                        }
                    )
                )
            }
            // 未完成的工具调用没有结果，请求会被服务端拒绝；压到检查点之前也再不会执行。
            // 保留区里的不发给压缩请求，压缩后照常留在检查点之后，不用检查
            check(nodes.subList(checkpointIndex + 1, endIndex).none { node ->
                node.currentMessage.getTools().any { it.isPending || it.canResumeExecution }
            }) { context.getString(R.string.chat_page_compress_handle_pending_tools) }

            // 必须和对话用同一个模型、同一套请求组装，才能命中提示词缓存
            val turn = requestFactory.createContext(settingsStore.awaitLoaded(), conversation)
            val summary = requestFactory.generateCompressionSummary(
                turn = turn,
                messages = conversation.currentMessages.take(endIndex),
                compressPrompt = buildCompressPrompt(
                    additionalPrompt = additionalPrompt,
                    locale = Locale.getDefault().displayName,
                ),
            )

            // 原消息原样保留，摘要插在压缩范围的最后一条消息之后。摘要生成期间对话可能已变化，
            // 因此按节点定位插入到最新状态。
            session.update { latest ->
                check(!session.isGenerating) { context.getString(R.string.chat_page_compress_blocked_generating) }
                val updated = latest.insertContextCheckpoint(
                    afterNodeId = nodes[endIndex - 1].id,
                    summary = summary,
                ) ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_conversation_changed))
                updated.copy(chatSuggestions = emptyList())
            }
            session.flush()
        }
    }

    // ---- 聊天页配置 ----

    /**
     * 聊天页对助手的修改：会话持有的字段只改当前会话，其余写回助手设置。
     *
     * @param update 接收会话视角下的助手，返回修改后的助手
     */
    suspend fun updateChatAssistant(conversationId: Uuid, update: (Assistant) -> Assistant) {
        withLoadedSession(conversationId) { session ->
            val settings = settingsStore.awaitLoaded()
            val conversation = session.state.value
            val stored = settings.getStoredAssistantOf(conversation)
            val updated = update(settings.getAssistantOf(conversation))
            val assistant = updated.withoutConversationFields(conversation, stored)
            session.updateMetadata(
                transform = { it.withAssistantUpdate(updated, settings) },
                persist = conversationRepo::updateConversationConfig,
            )
            if (assistant != stored) {
                settingsStore.update { latest ->
                    latest.copy(assistants = latest.assistants.map { if (it.id == assistant.id) assistant else it })
                }
            }
        }
    }

    /** 更新当前会话的显示状态栏开关；不影响状态生成和缓存。 */
    suspend fun updateShowAssistantStatus(conversationId: Uuid, enabled: Boolean) {
        withLoadedSession(conversationId) { session ->
            val updatedDraft = session.updateDraft { it.withAssistantStatusVisibility(enabled) }
            if (!updatedDraft) {
                session.updateMetadata(
                    transform = { it.withAssistantStatusVisibility(enabled) },
                    persist = conversationRepo::updateConversationConfig,
                )
            }
        }
    }

    /**
     * 切换搜索方式，传 null 的一项保持不变。
     * 模型内置搜索在会话开始前是模型自身的开关，开始后固定在会话上。
     */
    suspend fun updateChatSearch(
        conversationId: Uuid,
        enableWebSearch: Boolean? = null,
        builtInSearch: Boolean? = null,
    ) {
        withLoadedSession(conversationId) { session ->
            val conversation = session.state.value
            if (conversation.config != null) {
                session.updateMetadata(
                    transform = {
                        it.copy(
                            config = it.config?.let { config ->
                                config.copy(
                                    enableWebSearch = enableWebSearch ?: config.enableWebSearch,
                                    builtInSearch = builtInSearch ?: config.builtInSearch,
                                )
                            }
                        )
                    },
                    persist = conversationRepo::updateConversationConfig,
                )
                return@withLoadedSession
            }
            settingsStore.update { settings ->
                val assistant = settings.getAssistantOf(conversation)
                val model = settings.getChatModelOf(conversation)
                settings.copy(
                    assistants = if (enableWebSearch == null) {
                        settings.assistants
                    } else {
                        settings.assistants.map {
                            if (it.id == assistant.id) it.copy(enableWebSearch = enableWebSearch) else it
                        }
                    },
                    providers = if (builtInSearch == null || model == null) {
                        settings.providers
                    } else {
                        settings.providers.map { provider ->
                            provider.editModel(
                                model.copy(
                                    tools = if (builtInSearch) {
                                        model.tools + BuiltInTools.Search
                                    } else {
                                        model.tools - BuiltInTools.Search
                                    }
                                )
                            )
                        }
                    },
                )
            }
        }
    }

    // 工作目录和配置一起按列落库；还没落过库的新会话先留在内存里，发出第一条消息时一起写入
    suspend fun updateWorkspaceCwd(conversationId: Uuid, workspaceCwd: String?) {
        withLoadedSession(conversationId) { session ->
            session.updateMetadata(
                transform = { it.copy(workspaceCwd = workspaceCwd) },
                persist = conversationRepo::updateConversationConfig,
            )
        }
    }

    suspend fun updateConversationSystemPrompt(conversationId: Uuid, systemPrompt: String?) {
        updateColumn(
            conversationId = conversationId,
            transform = { it.copy(customSystemPrompt = systemPrompt) },
            persist = { conversationRepo.updateCustomSystemPrompt(conversationId, systemPrompt) },
        )
    }

    suspend fun updateTitle(conversationId: Uuid, title: String) {
        withLoadedSession(conversationId) { session ->
            if (session.state.value.config?.isGroupChat == true) settingsStore.awaitLoaded()
            session.updateMetadata(
                transform = { latest ->
                    val resolvedTitle = if (latest.config?.isGroupChat == true) {
                        settingsStore.settingsFlow.value.groupChats
                            .firstOrNull { it.conversationId == conversationId }?.name ?: latest.title
                    } else title
                    latest.copy(title = resolvedTitle)
                },
                persist = { conversationRepo.updateTitle(conversationId, it.title) },
            )
            if (!session.existsInDatabase) session.flush()
        }
    }

    /**
     * 用户在聊天页改的单列字段：已落库的会话只写这一列，生成中也不会顺带写入还没生成完的回复；
     * 还没落过库的新会话整份落库，留下记录（没有内容的新会话仍然不写）。
     */
    private suspend fun updateColumn(
        conversationId: Uuid,
        transform: (Conversation) -> Conversation,
        persist: suspend () -> Unit,
    ) {
        withLoadedSession(conversationId) { session ->
            session.updateMetadata(transform, persist = { persist() })
            if (!session.existsInDatabase) session.flush()
        }
    }

    // ---- 会话元数据 ----

    private suspend fun updateConversationMetadata(
        conversationId: Uuid,
        transform: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        withLoadedSession(conversationId, createIfMissing = false) { session ->
            session.updateMetadata(transform, persist)
        }
    }

    suspend fun toggleConversationPinned(conversationId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            transform = { it.copy(isPinned = !it.isPinned) },
            persist = { conversationRepo.updatePinStatus(conversationId, it.isPinned) },
        )
    }

    suspend fun moveConversationToAssistant(conversationId: Uuid, assistantId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            // 文件夹属于助手，移动后清除原助手的文件夹归属。
            transform = { it.copy(assistantId = assistantId, folderId = null) },
            persist = { conversationRepo.updateConversationAssistant(conversationId, it.assistantId) },
        )
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     * 有 session 的会话先改内存，免得之后整份落库时写回旧的文件夹；没有的只改这一列，不必加载整个会话。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessionManager.get(conversationId) == null) {
            conversationRepo.updateConversationFolderId(conversationId, folderId)
            // 写库期间页面可能刚好打开了这个会话，加载时读到的可能是旧值，再同步一次内存
            if (sessionManager.get(conversationId) == null) return
        }
        updateConversationMetadata(
            conversationId = conversationId,
            transform = { it.copy(folderId = folderId) },
            persist = { conversationRepo.updateConversationFolderId(conversationId, folderId) },
        )
    }

    /**
     * 还没开始的新会话跟随抽屉里选中的文件夹，首次落库时归入；已经落库的会话不受影响，移动走 [moveConversationToFolder]。
     */
    suspend fun updateDraftConversationFolder(conversationId: Uuid, folderId: Uuid?) {
        val folder = folderId?.let { folderRepository.getFolderById(it) }
        withLoadedSession(conversationId) { session ->
            session.updateDraft { latest ->
                // 文件夹属于助手：已被删除或不属于会话的助手时不归入
                val target = folder?.takeIf { it.assistantId == latest.assistantId }?.id
                if (latest.folderId == target) latest else latest.copy(folderId = target)
            }
        }
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessionManager.snapshot().any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     * 已加载的会话先改内存再删库，之后落库不会写回已删除的文件夹。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessionManager.snapshot()
            .filter { it.isInitialized && it.state.value.folderId == folderId }
            .forEach { session ->
                // 这一列由下面删文件夹时统一清空，这里只改内存，不让会话因此变成有未落库的修改
                session.updateMetadata(
                    transform = { if (it.folderId == folderId) it.copy(folderId = null) else it },
                    persist = {},
                )
            }
        folderRepository.deleteFolder(folderId)
    }

    // ---- 删除会话 ----

    /**
     * 删除会话：停掉生成后在会话的写锁内删库，内存换成一个还没落过库的新会话。
     * 还停在这个会话上的页面看到的是空会话，之后再发消息会新建记录，不会写回已删除的那条。
     */
    suspend fun deleteConversation(conversationId: Uuid) {
        val session = sessionManager.getOrCreate(conversationId)
        discardQueuedMessages(session)
        session.replace(newConversation(conversationId), existsInDatabase = false) { previous ->
            conversationRepo.getConversationById(conversationId)?.let { conversationRepo.deleteConversation(it) }
            // 数据库那份不知道只在内存里出现过的附件
            previous?.let { filesManager.deleteChatFiles(it.files) }
        }
        sessionManager.removeIfIdle(session)
    }

    /** 撤销删除：写回删除前的会话，还停在这个会话上的页面也一并换回 */
    suspend fun restoreConversation(conversation: Conversation) {
        val session = sessionManager.getOrCreate(conversation.id)
        session.replace(conversation, existsInDatabase = true) {
            conversationStore.write(previous = null, current = conversation)
        }
        sessionManager.removeIfIdle(session)
    }

    suspend fun deleteConversationsOfAssistant(assistantId: Uuid) {
        val persisted = conversationRepo.getConversationsOfAssistant(assistantId).first().map { it.id }
        val loaded = sessionManager.snapshot()
            .filter { it.isInitialized && it.state.value.assistantId == assistantId }
            .map { it.id }
        (persisted + loaded).distinct().forEach { deleteConversation(it) }
    }

    private fun discardQueuedMessages(session: ConversationSession) {
        session.messageQueue.pause()
        session.messageQueue.state.value.messages.forEach { queued ->
            session.messageQueue.remove(queued.id)?.let(::cleanupQueuedAttachments)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            sessionManager.withSession<Unit>(conversationId) { session ->
                try {
                    ensureInitialized(session)
                    val settings = settingsStore.awaitLoaded()

                    val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n\n") { it.text }
                        .trim()

                    if (messageText.isBlank()) return@withSession

                    // Set loading state for translation
                    val loadingText = context.getString(R.string.translating)
                    session.update { it.withTranslation(message.id, loadingText) }

                    translationHandler.translateText(
                        settings = settings,
                        sourceText = messageText,
                        targetLanguage = targetLanguage
                    ) { translatedText ->
                        // Update translation field in real-time
                        session.update { it.withTranslation(message.id, translatedText) }
                    }.collect { /* Final translation already handled in onStreamUpdate */ }

                    session.flush()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Clear translation field on error
                    if (session.isInitialized) session.update { it.withTranslation(message.id, null) }
                    addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
                }
            }
        }
    }

    suspend fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        mutate(conversationId) { it.withTranslation(messageId, null) }
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return
        val settings = settingsStore.awaitLoaded()

        mutate(conversationId) { conversation ->
            conversation.editMessage(messageId, parts.replaceUserRegexes(settings.getStoredAssistantOf(conversation)))
        }
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val source = withLoadedSession(conversationId) { it.state.value }
        val existingTitles = conversationRepo.getTitlesOfAssistant(source.assistantId)
        val fork = source.fork(messageId, existingTitles, copyFile = ::copyChatFile)
            ?: throw NotFoundException("Message not found")
        // 新会话直接落库，打开时再从数据库加载
        val forkConversation = conversationStore.prepareWrite()(fork)
        conversationRepo.insertConversation(forkConversation)
        memoryConsolidationScheduler.onConversationForked(forkConversation)
        return forkConversation
    }

    private fun copyChatFile(url: String): String =
        filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()?.toString() ?: url

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        mutate(conversationId) { conversation ->
            val targetNode = conversation.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw NotFoundException("Message node not found")
            if (selectIndex !in targetNode.messages.indices) {
                throw BadRequestException("Invalid selectIndex")
            }
            if (targetNode.selectIndex == selectIndex) return@mutate conversation
            conversation.selectBranch(nodeId, selectIndex)
        }
    }

    // 收藏记录在单独的表里，节点上的标记只用于显示，不需要落库
    suspend fun setNodeFavorite(conversationId: Uuid, nodeId: Uuid, favorite: Boolean) {
        val session = sessionManager.get(conversationId)?.takeIf { it.isInitialized } ?: return
        session.updateMetadata(transform = { it.withNodeFavorite(nodeId, favorite) }, persist = {})
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        mutate(conversationId) { conversation ->
            conversation.deleteMessage(messageId)
                ?: if (failIfMissing) throw NotFoundException("Message not found") else conversation
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessionManager.get(conversationId) ?: return
        val jobs = synchronized(session) {
            session.messageQueue.pause()
            session.cancelJobs()
        }
        if (jobs.isEmpty()) return
        jobs.forEach { it.join() }
        if (session.isInitialized) finishInterruptedPendingTools(session)
    }
}
