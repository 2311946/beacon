package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.ai.prompts.MemoryConsolidationConversation
import me.rerere.rikkahub.data.ai.prompts.buildMemoryConsolidationInput
import me.rerere.rikkahub.data.ai.prompts.buildMemoryConsolidationPrompt
import me.rerere.rikkahub.data.ai.prompts.buildScopedMemoryConsolidationPrompt
import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.tools.createScopedMemoryConversationTools
import me.rerere.rikkahub.data.model.MemoryWriteContext
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemorySourceKind
import me.rerere.rikkahub.data.ai.tools.dialogueMessages
import me.rerere.rikkahub.data.ai.tools.memoryWritePath
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.dao.MemoryConsolidationDAO
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId
import kotlin.uuid.Uuid

private const val TAG = "MemoryConsolidator"

private const val MAX_STEPS = 32
private const val MAX_CONVERSATIONS_PER_RUN = 8

// 候选 ID 分页读取，实际待整理数量在权限和轮次确认后再限制。
private const val MEMORY_CANDIDATE_PAGE_SIZE = 50
private const val MAX_TURNS_PER_CONVERSATION = 12
private const val MAX_TRANSCRIPT_LENGTH = 60_000
private const val RECENT_CONTEXT_MESSAGES = 6
private const val RECENT_CONTEXT_MESSAGE_LENGTH = 500
private const val USER_MESSAGE_LENGTH = 8000
private const val ASSISTANT_MESSAGE_LENGTH = 2000

// 更早的对话不再补整理：刚开启记忆或导入了一批旧对话时，不该把整段历史都交给模型
private const val PENDING_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/**
 * 后台记忆整理：把各个对话里上次整理之后新增的轮次交给一个只带记忆工具和对话查询工具的 agent，
 * 由它决定哪些事实值得长期保存。
 *
 * 主对话默认不写记忆，只在用户明确要求时才写；日常的积累都靠这里。
 * 什么时候整理由 [MemoryConsolidationScheduler] 决定。
 */
class MemoryConsolidator(
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val consolidationDao: MemoryConsolidationDAO,
    private val settingsStore: SettingsStore,
) : MemoryConsolidationWorker {

    override suspend fun pendingOf(conversation: Conversation): PendingMemoryConversation? =
        pendingAllOf(conversation).firstOrNull()

    override suspend fun pendingAllOf(conversation: Conversation): List<PendingMemoryConversation> =
        withContext(Dispatchers.IO) {
            settingsStore.awaitLoaded().memoryTargets(conversation).mapNotNull { target ->
                pendingFor(conversation, target)
            }
        }

    private suspend fun pendingFor(conversation: Conversation, target: MemoryConsolidationTarget): PendingMemoryConversation? {
        val pending = loadPendingTurns(conversation, target)
        return if (pending.turns.isEmpty()) null else PendingMemoryConversation(
            conversationId = conversation.id,
            memoryId = target.memoryId,
            turns = pending.totalTurns,
            automatic = target.assistant.autoConsolidateMemory,
        )
    }

    override suspend fun memoryIdsWithUpdates(): List<String> = withContext(Dispatchers.IO) {
        val settings = settingsStore.awaitLoaded()
        val since = System.currentTimeMillis() - PENDING_WINDOW_MS
        settings.assistants.filter { it.enableMemory }.flatMap { assistant ->
            val groups = settings.memoryGroupsFor(assistant.id)
            val candidates = collectMemoryCandidateIds(MEMORY_CANDIDATE_PAGE_SIZE) { limit, offset ->
                consolidationDao.getUpdatedConversationIdsForAssistant(
                    assistant.id.toString(), groups.mapNotNull { it.conversationId?.toString() },
                    since, limit, offset,
                )
            }
            candidates.flatMap { id ->
                conversationRepo.getConversationById(Uuid.parse(id))?.let { conversation ->
                    settings.memoryTargets(conversation).filter { it.assistant.id == assistant.id }.map { it.memoryId }
                }.orEmpty()
            }
        }.distinct()
    }

    override suspend fun findPending(memoryId: String): List<PendingMemoryConversation> = withContext(Dispatchers.IO) {
        val settings = settingsStore.awaitLoaded()
        val since = System.currentTimeMillis() - PENDING_WINDOW_MS
        settings.assistants.filter { it.enableMemory && (it.memoryId == memoryId || it.id.toString() == memoryId) }
            .flatMap { assistant ->
                val groups = settings.memoryGroupsFor(assistant.id)
                val candidates = collectMemoryCandidateIds(MEMORY_CANDIDATE_PAGE_SIZE) { limit, offset ->
                    consolidationDao.getUpdatedConversationIdsForAssistant(
                        assistant.id.toString(), groups.mapNotNull { it.conversationId?.toString() },
                        since, limit, offset,
                    )
                }
                collectPending(candidates, MAX_CONVERSATIONS_PER_RUN) { id ->
                    val conversation = conversationRepo.getConversationById(Uuid.parse(id)) ?: return@collectPending null
                    val target = settings.memoryTargets(conversation).firstOrNull {
                        it.assistant.id == assistant.id && it.memoryId == memoryId
                    } ?: return@collectPending null
                    pendingFor(conversation, target)
                }
            }.distinctBy { it.conversationId }
    }

    override suspend fun markConsolidated(conversation: Conversation) {
        withContext(Dispatchers.IO) {
            settingsStore.awaitLoaded().memoryTargets(conversation).forEach { target ->
                advance(conversation.id, target.assistant.id, maxOf(System.currentTimeMillis(), conversation.updateAt.toEpochMilli()))
            }
        }
    }

    override suspend fun consolidate(
        memoryId: String,
        conversationIds: Set<Uuid>,
        fullReview: Boolean,
    ): MemoryConsolidationRun? = withContext(Dispatchers.IO) {
        try {
            consolidateStore(memoryId, conversationIds, fullReview)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logging.log(TAG, "consolidate $memoryId: $e")
            throw e
        }
    }

    private suspend fun consolidateStore(
        memoryId: String,
        conversationIds: Set<Uuid>,
        fullReview: Boolean,
    ): MemoryConsolidationRun? {
        val settings = settingsStore.awaitLoaded()
        val assistants = settings.assistants.filter {
            it.enableMemory && (it.memoryId == memoryId || it.id.toString() == memoryId)
        }
        var combined: MemoryConsolidationRun? = null
        for ((assistant, review) in memoryConsolidationPasses(assistants, fullReview)) {
            // 同一共享库只由最后一个独立目标做全库整理，不合并任何助手的私聊权限。
            val pass = consolidateForAssistant(
                settings,
                assistant,
                memoryId,
                conversationIds,
                review,
            )
            if (pass != null) combined = combined?.let { it + pass } ?: pass
        }
        return combined
    }

    private suspend fun consolidateForAssistant(
        settings: Settings,
        assistant: Assistant,
        memoryId: String,
        conversationIds: Set<Uuid>,
        fullReview: Boolean,
    ): MemoryConsolidationRun? {
        val batches = conversationIds.mapNotNull { conversationRepo.getConversationById(it) }
            .sortedByDescending { it.updateAt }.mapNotNull { conversation ->
                val target = settings.memoryTargets(conversation).firstOrNull {
                    it.assistant.id == assistant.id && it.memoryId == memoryId
                } ?: return@mapNotNull null
                val pending = loadPendingTurns(conversation, target)
                if (pending.turns.isEmpty()) null else Batch(conversation, pending, target)
            }.withinTranscriptBudget()
        val files = memoryRepository.getActiveFiles(memoryId)
        // 没有新的轮次时，只有手动清理且记忆库里有东西才值得调用模型
        if (batches.isEmpty() && (!fullReview || files.isEmpty())) return null

        val (model, reasoningLevel) = settings.consolidationModel(
            batches.firstOrNull()?.conversation,
            assistant
        )
            ?: error("No model available")
        val providerSetting = model.findProvider(settings.providers) ?: error("Provider not found")
        val allowedGroups = if (memoryId == assistant.id.toString()) settings.memoryGroupsFor(assistant.id) else emptyList()
        val shownSources = linkedMapOf<String, MemorySource>()
        suspend fun recordShown(conversation: Conversation, messages: List<UIMessage>) {
            val current = settingsStore.awaitLoaded()
            val latest = conversationRepo.getConversationById(conversation.id)
                ?: error("Source conversation was deleted")
            val target = current.memoryTargets(latest).firstOrNull { it.assistant.id == assistant.id && it.memoryId == memoryId }
                ?: error("Conversation is no longer authorized for this assistant")
            check(latest.currentMessages == conversation.currentMessages && latest.config?.speakerMap == conversation.config?.speakerMap) {
                "Source conversation changed before it was shown"
            }
            messages.forEach { message ->
                // 他人/未知作者的发言可辅助理解，但不能成为目标角色新记忆的写入证据。
                if (memorySpeakerRelation(conversation, message, assistant.id, target.group) in setOf("user", "self")) {
                    memorySourceOf(conversation, message, target.group)?.let { source ->
                        shownSources[memorySourceRef(conversation.id, message.id)] = source
                    }
                }
            }
        }
        batches.forEach { recordShown(it.conversation, it.shownMessages) }
        suspend fun checkAccess() {
            val current = settingsStore.awaitLoaded()
            val stored = current.getAssistantById(assistant.id)
            check(stored?.enableMemory == true && (stored.memoryId == memoryId || stored.id.toString() == memoryId)) {
                "Memory ownership changed during consolidation"
            }
            batches.forEach { batch ->
                val latest = conversationRepo.getConversationById(batch.conversation.id)
                    ?: error("Source conversation was deleted")
                check(current.memoryTargets(latest).any {
                    it.assistant.id == assistant.id && it.memoryId == memoryId && it.group?.id == batch.target.group?.id
                }) { "Group membership or conversation ownership changed during consolidation" }
            }
        }
        suspend fun contextFor(refs: List<String>): MemoryWriteContext {
            checkAccess()
            check(refs.isNotEmpty() || fullReview) { "source_refs is required for new memory facts" }
            val current = settingsStore.awaitLoaded()
            val sources = refs.map { ref ->
                val original = shownSources[ref] ?: error("Source reference was not shown to this organizer")
                val conversation = conversationRepo.getConversationById(Uuid.parse(requireNotNull(original.conversationId)))
                    ?: error("Source conversation was deleted")
                val target = current.memoryTargets(conversation).firstOrNull { it.assistant.id == assistant.id && it.memoryId == memoryId }
                    ?: error("Source conversation is no longer authorized")
                val message = conversation.currentMessages.firstOrNull { it.id.toString() == original.messageId }
                    ?: error("Source message is no longer on the current branch")
                check(memorySourceOf(conversation, message, target.group) == original) { "Source message changed during consolidation" }
                original
            }
            // 引用由程序生成并复核；这是文件级证据引用，不是逐句事实验证或亲历认证。
            return MemoryWriteContext(sources = sources.ifEmpty { listOf(MemorySource(MemorySourceKind.UNKNOWN)) })
        }
        // 自动整理只增改；一次 agent 只持有一个目标助手的私聊权限和明确群授权。
        val tools = buildMemoryTools(
            memoryRepository, memoryId, ::contextFor, ::checkAccess,
            validateWrite = { path, candidate, refs ->
                if (refs.isEmpty()) {
                    check(fullReview) { "source_refs is required for new memory facts" }
                    // 无聊天证据的手动整理仅能复用当前活跃记忆的完整行（含元数据）。
                    // 不把旧记忆搬运冒充为当前聊天事实证明，也不允许凭空改写新事实。
                    MemoryReviewGuard(memoryRepository.getActiveFiles(memoryId)).validateWrite(path, candidate)
                }
            },
        ).filter { fullReview || it.name != MemoryToolNames.DELETE } +
            createScopedMemoryConversationTools(
                conversationRepo, assistant.id, allowedGroups,
                settings.groupChats.mapNotNull { it.conversationId }.toSet(), ::recordShown,
                allowPrivateChats = assistant.memoryId == memoryId,
            )
        val systemPrompt = buildMemoryConsolidationPrompt(fullReview) + "\n" +
            buildScopedMemoryConsolidationPrompt(assistant.id, memoryId)
        // 整次整理的每一步用同一个会话 ID，服务端才能命中前面几步的缓存
        val params = backgroundTextGenerationParams(model, Uuid.random(), reasoningLevel)
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                val system = (listOf(systemPrompt) + tools.map { it.systemPrompt(model, messages) })
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                return StepRequest(messages = listOf(UIMessage.system(system)) + messages, params = params)
            }
        }

        val finished = runAgent(
            request = AgentRequest(
                provider = usageRecorder.wrap(
                    providerManager.getProviderByType(providerSetting),
                    UsageTag(UsageSource.MEMORY),
                ),
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.user(
                        buildMemoryConsolidationInput(
                            files = files,
                            conversations = batches.map { it.input },
                        )
                    )
                ),
                tools = tools,
                stream = false,
                maxSteps = MAX_STEPS,
                retry = settings.retryPolicy(),
            ),
            hooks = hooks,
        ).filterIsInstance<AgentEvent.Finished>().single()
        check(finished.reason != AgentFinishReason.StepLimitReached) {
            "Memory consolidation stopped after $MAX_STEPS steps; progress was not advanced"
        }
        checkAccess()
        // 生成中途失败或来源分支变化不推进；每个成员独立写自己的进度。
        val advanced = batches.filter { batch ->
            val latest = conversationRepo.getConversationById(batch.conversation.id)
            check(latest?.currentMessages == batch.conversation.currentMessages && latest.config?.speakerMap == batch.conversation.config?.speakerMap) {
                "Source conversation changed; consolidation progress was not advanced"
            }
            batch.pending.consolidatedThrough?.let { advance(batch.conversation.id, assistant.id, it) } == true
        }

        val steps = finished.message.getTools()
        val touched = steps.mapNotNull { it.memoryWritePath() }
            .map { MemoryFile.normalizePath(it) ?: it }
            .distinct()
        val remaining = memoryRepository.getActiveFiles(memoryId).associateBy { it.path }
        return MemoryConsolidationRun(
            memoryId = memoryId,
            finishedAt = System.currentTimeMillis(),
            fullReview = fullReview,
            changedPaths = touched.filter { it in remaining },
            deletedPaths = touched.filterNot { it in remaining },
            titles = touched.mapNotNull { path -> remaining[path]?.let { path to it.title } }.toMap(),
            advancedConversations = advanced.map { it.conversation.id }.toSet(),
        )
    }

    private suspend fun loadPendingTurns(conversation: Conversation, target: MemoryConsolidationTarget): PendingMemoryTurns {
        val messages = conversation.dialogueMessages()
        val groupChat = target.group != null
        val consolidatedAt = consolidationDao.getConsolidatedAt(conversation.id.toString(), target.assistant.id.toString())
            ?: initialMemoryProgress(messages, groupChat = groupChat).also { advance(conversation.id, target.assistant.id, it) }
        val pending = pendingMemoryTurns(
            messages = messages,
            updatedAt = conversation.updateAt.toEpochMilli(),
            consolidatedAt = consolidatedAt,
            groupChat = groupChat,
        )
        if (pending.turns.isEmpty()) pending.consolidatedThrough?.let { advance(conversation.id, target.assistant.id, it) }
        return pending
    }

    // 返回进度是否写进去了
    private suspend fun advance(conversationId: Uuid, assistantId: Uuid, consolidatedAt: Long): Boolean = runCatching {
        consolidationDao.upsert(MemoryConsolidationEntity(conversationId.toString(), consolidatedAt, assistantId.toString()))
    }.onFailure {
        // 对话在整理期间被删掉时外键约束会拒绝写入
        Logging.log(TAG, "advance $conversationId: $it")
    }.isSuccess
}

private class Batch(val conversation: Conversation, val pending: PendingMemoryTurns, val target: MemoryConsolidationTarget) {
    private val recent = conversation.dialogueMessages().withIndex()
        .filterNot { it.value.isContextCheckpoint || it.value.isSynthetic }
        .filter { it.index < pending.turns.first().position }.takeLast(RECENT_CONTEXT_MESSAGES)
    val shownMessages: List<UIMessage> = (recent + pending.turns.flatMap { it.messages })
        .map { it.value }.filter { it.toText().isNotBlank() }.distinctBy { it.id }
    val input: MemoryConsolidationConversation by lazy {
        MemoryConsolidationConversation(
            id = conversation.id.toString(),
            title = conversation.title,
            recentContext = recent.toScopedMemoryTranscript(
                conversation, target, RECENT_CONTEXT_MESSAGE_LENGTH, RECENT_CONTEXT_MESSAGE_LENGTH,
            ),
            newTurns = pending.turns.flatMap { it.messages }.toScopedMemoryTranscript(
                conversation, target, USER_MESSAGE_LENGTH, ASSISTANT_MESSAGE_LENGTH,
            ),
            sourceKind = if (target.group == null) "PRIVATE_CHAT" else "GROUP_CHAT",
            groupId = target.group?.id?.toString(),
            targetAssistantId = target.assistant.id.toString(),
        )
    }
}

// 超出的对话留在待整理里，这次整理完后会接着处理；至少带上一个，否则永远轮不到
private fun List<Batch>.withinTranscriptBudget(): List<Batch> {
    var length = 0
    return take(MAX_CONVERSATIONS_PER_RUN).filterIndexed { index, batch ->
        length += batch.input.newTurns.length
        index == 0 || length <= MAX_TRANSCRIPT_LENGTH
    }
}

// 依次用记忆整理模型、快速模型，推理强度跟着用上的那个；整理要调用工具，都不支持时退回对话模型
private fun Settings.consolidationModel(
    conversation: Conversation?,
    assistant: Assistant,
): Pair<Model, ReasoningLevel>? {
    fun toolModel(id: Uuid?) = id?.let { findModelById(it) }?.takeIf { ModelAbility.TOOL in it.abilities }
    val (model, reasoningLevel) = toolModel(memoryModelId)?.let { it to memoryModelReasoningLevel }
        ?: toolModel(fastModelId)?.let { it to fastModelReasoningLevel }
        ?: (conversation?.let { getChatModelOf(it) } ?: findModelById(assistant.chatModelId ?: chatModelId))
            ?.let { it to fastModelReasoningLevel }
        ?: return null
    return model.copy(tools = emptySet()) to reasoningLevel
}

/** 先固定全部候选 ID；后续轮次检查会推进进度，边检查边 OFFSET 会因候选退出查询而漏项。 */
internal suspend fun collectMemoryCandidateIds(
    pageSize: Int,
    loadPage: suspend (limit: Int, offset: Int) -> List<String>,
): List<String> {
    val candidates = mutableListOf<String>()
    var offset = 0
    while (true) {
        val page = loadPage(pageSize, offset)
        candidates.addAll(page)
        if (page.size < pageSize) return candidates
        offset += page.size
    }
}

/**
 * 依次检查 [candidates]，收集前 [limit] 个确实有待整理轮次的。
 *
 * 数量上限要在确认之后才算：候选是按更新时间粗筛的，重新生成过回复、最后一轮没结束的对话都在里面，
 * 先截断再确认的话，排在它们后面真正有新轮次的对话会一直轮不到。
 */
internal suspend fun <T> collectPending(
    candidates: List<T>,
    limit: Int,
    check: suspend (T) -> PendingMemoryConversation?,
): List<PendingMemoryConversation> {
    val found = mutableListOf<PendingMemoryConversation>()
    for (candidate in candidates) {
        if (found.size >= limit) break
        check(candidate)?.let(found::add)
    }
    return found
}

/** 一轮对话：一条用户消息和它之后直到下一条用户消息之前的回复，消息带着在对话里的位置 */
internal class MemoryTurn(val messages: List<IndexedValue<UIMessage>>) {
    val position: Int get() = messages.first().index
    val userMessage: UIMessage get() = messages.first().value

    // 回复还没出来，或者停在等待工具审批上
    val isFinished: Boolean
        get() = messages.last().value.let { last ->
            last.role == MessageRole.ASSISTANT && last.getTools().all { it.isExecuted }
        }
}

internal class PendingMemoryTurns(
    /** 这次要整理的轮次，积压多时只是最早的一批 */
    val turns: List<MemoryTurn>,
    /** 还没整理的总轮数，不小于 [turns] 的数量 */
    val totalTurns: Int,
    /** 整理完 [turns] 后进度应推进到的时间，没有可推进的内容时为 null */
    val consolidatedThrough: Long?,
)

// 按用户消息切成轮；压缩检查点是模型写的摘要，不是用户说的话
private fun memoryTurnsOf(messages: List<UIMessage>, groupChat: Boolean = false): List<MemoryTurn> {
    val indexed = messages.withIndex().filterNot { it.value.isContextCheckpoint || it.value.isSynthetic }
    // 群连续接话没有新的 USER；每个完整发言也是新单元，不能跟着早已整理的用户轮次一起跳过。
    val starts = indexed.indices.filter {
        indexed[it].value.role == MessageRole.USER || (groupChat && indexed[it].value.role == MessageRole.ASSISTANT &&
            (it == 0 || indexed[it - 1].value.role != MessageRole.USER))
    }
    return starts.mapIndexed { i, start ->
        MemoryTurn(indexed.subList(start, starts.getOrElse(i + 1) { indexed.size }))
    }
}

// 只发了图片之类没有文字的轮次没有可提取的内容
private val MemoryTurn.hasUserText: Boolean get() = userMessage.toText().isNotBlank()

/**
 * 第一次跟踪一个对话时的整理进度：只回看最近 [MAX_TURNS_PER_CONVERSATION] 轮。
 *
 * 助手后来才开启记忆，或者对话是导入的，都可能带着很长的历史，不该全部交给模型。
 * 只有这里会略过旧的轮次；进度定下来之后，它后面的内容都会被整理到。
 */
internal fun initialMemoryProgress(
    messages: List<UIMessage>,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    groupChat: Boolean = false,
): Long {
    val first = memoryTurnsOf(messages, groupChat).filter { it.hasUserText }.takeLast(MAX_TURNS_PER_CONVERSATION).firstOrNull()
        ?: return 0
    return first.userMessage.createdAt.toInstant(timeZone).toEpochMilliseconds() - 1
}

/**
 * 从 [messages]（当前分支上用户和助手的消息）里找出 [consolidatedAt] 之后新增的轮次。
 *
 * 按用户消息的创建时间判断而不是按下标：重新生成不会改用户消息的时间，所以不会重复整理；
 * 编辑用户消息会产生一条新消息，会被当作新的一轮。
 */
internal fun pendingMemoryTurns(
    messages: List<UIMessage>,
    updatedAt: Long,
    consolidatedAt: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    groupChat: Boolean = false,
): PendingMemoryTurns {
    fun UIMessage.time() = createdAt.toInstant(timeZone).toEpochMilliseconds()

    val turns = memoryTurnsOf(messages, groupChat)
    var pending = turns.filter { it.userMessage.time() > consolidatedAt }
    var consolidatedThrough = maxOf(updatedAt, turns.lastOrNull()?.messages?.last()?.value?.time() ?: 0)
    val last = turns.lastOrNull()
    if (last != null && !last.isFinished) {
        // 最后一轮还没结束：留到下次，进度停在它前面
        // 同一时间戳必须一起完成后再推进，否则先完成的发言会把未完成的同刻发言跨过去。
        pending = pending.filter { it !== last && it.userMessage.time() < last.userMessage.time() }
        consolidatedThrough = last.userMessage.time() - 1
    }

    pending = pending.filter { it.hasUserText }
    var batchSize = minOf(MAX_TURNS_PER_CONVERSATION, pending.size)
    // 时间戳进度无法区分同一时间的轮次，切点处必须一起交给模型，避免尾部被永久跳过。
    while (batchSize < pending.size && pending[batchSize].userMessage.time() == pending[batchSize - 1].userMessage.time()) {
        batchSize++
    }
    val batch = pending.take(batchSize)
    if (batch.isNotEmpty()) {
        val batchEnd = batch.last().userMessage.time()
        // 积压超过一次能处理的量（比如模型连续失败）时从最早的开始，进度只推进到这一批为止，
        // 剩下的留给下一次；如果直接推进到对话末尾，没交给模型的轮次就再也不会被整理了
        pending.drop(batch.size).firstOrNull { it.userMessage.time() > batchEnd }?.let { next ->
            consolidatedThrough = next.userMessage.time() - 1
        }
        // 交给模型的轮次一定要算整理过。时间相同的轮次靠进度分不开（导入的对话可能整段都是同一个时间），
        // 进度停在它们前面的话就推不动了，同一批会被一遍遍地整理
        consolidatedThrough = maxOf(consolidatedThrough, batchEnd)
    }

    return PendingMemoryTurns(
        turns = batch,
        totalTurns = pending.size,
        consolidatedThrough = consolidatedThrough.takeIf { it > consolidatedAt },
    )
}

/**
 * 转成给整理器看的文本，每条消息带上位置编号，方便它用 conversation_read 读被截断的部分。
 *
 * 主对话里按用户要求改过记忆的地方单独标一行，整理器要以用户的明确修改为准。
 */
internal fun List<IndexedValue<UIMessage>>.toMemoryTranscript(userLength: Int, assistantLength: Int): String =
    flatMap { (position, message) ->
        val maxLength = if (message.role == MessageRole.USER) userLength else assistantLength
        val text = message.toText().trim().let { if (it.length > maxLength) it.take(maxLength) + "..." else it }
        val memoryWrites = message.getTools().mapNotNull { tool ->
            tool.memoryWritePath()?.let { "[assistant updated memory: ${tool.toolName} $it]" }
        }
        listOfNotNull("[${message.role.name} #$position]: $text".takeIf { text.isNotEmpty() }) + memoryWrites
    }.joinToString("\n\n")
