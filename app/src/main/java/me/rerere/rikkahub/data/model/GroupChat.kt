package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 群聊配置
 *
 * 一个群聊 = 若干助手（成员）在同一个会话里轮流发言。
 * 群聊本身不持有消息，消息仍然存放在关联的 [Conversation] 中，
 * 每条消息是谁说的记录在 ConversationConfig.speakerMap 里。
 */
@Serializable
data class GroupChat(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val memberIds: List<Uuid> = emptyList(),    // 引用 settings.assistants
    // 保留旧 JSON 键。旧版本始终 encodeDefaults，已有群的四个值会成为显式覆盖。
    @SerialName("activationStrategy")
    val legacyActivationStrategy: GroupActivationStrategy? = null,
    val disabledMemberIds: List<Uuid> = emptyList(),  // 禁言成员
    val conversationId: Uuid? = null,  // 关联的 Conversation ID
    val allowSelfResponses: Boolean = false, // 允许连续同一个人发言
    @SerialName("autoModeDelay")
    val legacyAutoModeDelay: Int? = null,
    @SerialName("autoChatRounds")
    val legacyAutoChatRounds: Int? = null,
    val enabled: Boolean = true,
    val chatModelId: Uuid? = null,  // 群聊级模型覆盖（不选则用各成员自己的模型）
    @SerialName("singleChatContextCount")
    val legacySingleChatContextCount: Int? = null,
    // 缺少新键时迁移旧值；新键显式为 null 时必须继承，不能再次回退到旧值。
    val activationStrategyOverride: GroupActivationStrategy? = legacyActivationStrategy,
    val autoModeDelayOverride: Int? = legacyAutoModeDelay,
    val autoChatRoundsOverride: Int? = legacyAutoChatRounds,
    val singleChatContextCountOverride: Int? = legacySingleChatContextCount,
    val avatar: Avatar = Avatar.Dummy,
    val systemPromptOverride: String? = null, // null 继承全局；空字符串不附加群聊提示词
    val autoSummaryEnabledOverride: Boolean? = null,
    val autoSummaryMessageThresholdOverride: Int? = null,
    val summaryKeepRecentMessagesOverride: Int? = null,
) {
    /** 生成和展示时传入当前 Settings.groupChatDefaults，不要把解析结果写回群配置。 */
    fun resolved(defaults: GroupChatDefaults): GroupChatDefaults {
        val summaryThreshold = (autoSummaryMessageThresholdOverride ?: defaults.autoSummaryMessageThreshold).coerceIn(40, 300)
        return GroupChatDefaults(
            activationStrategy = activationStrategyOverride ?: defaults.activationStrategy,
            autoChatRounds = (autoChatRoundsOverride ?: defaults.autoChatRounds).coerceIn(1, 10),
            autoModeDelay = (autoModeDelayOverride ?: defaults.autoModeDelay).coerceIn(0, 60),
            singleChatContextCount = normalizeChatContextCount(singleChatContextCountOverride ?: defaults.singleChatContextCount),
            systemPrompt = systemPromptOverride ?: defaults.systemPrompt,
            autoSummaryEnabled = autoSummaryEnabledOverride ?: defaults.autoSummaryEnabled,
            autoSummaryMessageThreshold = summaryThreshold,
            summaryKeepRecentMessages = (summaryKeepRecentMessagesOverride ?: defaults.summaryKeepRecentMessages)
                .coerceIn(0, minOf(50, summaryThreshold - 1)),
        )
    }

    // 兼容尚未接入全局默认值的调用方；实际运行配置应通过 resolved 读取。
    val activationStrategy: GroupActivationStrategy get() = resolved(GroupChatDefaults()).activationStrategy
    val autoChatRounds: Int get() = resolved(GroupChatDefaults()).autoChatRounds
    val autoModeDelay: Int get() = resolved(GroupChatDefaults()).autoModeDelay
    val singleChatContextCount: Int get() = resolved(GroupChatDefaults()).singleChatContextCount
}

const val DEFAULT_GROUP_CHAT_SYSTEM_PROMPT = """回复时使用小说风格，在保留角色原有人设、性格与说话习惯的基础上自然参与群聊。
对白使用“引号”包裹，动作描写使用*星号*包裹，心理描写使用（括号）包裹。
只描写当前角色自己的对白、动作和心理，不代替其他成员或用户发言，也不擅自决定他人的行为或心理。"""

@Serializable
data class GroupChatDefaults(
    val activationStrategy: GroupActivationStrategy = GroupActivationStrategy.LIST,
    val autoChatRounds: Int = 2,
    val autoModeDelay: Int = 3, // 自动接话延迟（秒），0=立即
    val singleChatContextCount: Int = 5, // 单聊/群聊上下文互通条数，范围 1–10
    val systemPrompt: String = DEFAULT_GROUP_CHAT_SYSTEM_PROMPT,
    val autoSummaryEnabled: Boolean = true,
    val autoSummaryMessageThreshold: Int = 80,
    val summaryKeepRecentMessages: Int = 20,
)

internal fun normalizeChatContextCount(count: Int): Int = if (count <= 0) 5 else count.coerceAtMost(10)

/** 仅合并本页实际修改的字段，保留运行中建立的 conversationId、模型和其他设置。 */
internal fun mergeGroupSettingsDraft(latest: GroupChat, base: GroupChat, draft: GroupChat): GroupChat = latest.copy(
    name = if (draft.name != base.name) draft.name.trim() else latest.name,
    avatar = if (draft.avatar != base.avatar) draft.avatar else latest.avatar,
    memberIds = if (draft.memberIds != base.memberIds) draft.memberIds else latest.memberIds,
    disabledMemberIds = if (draft.disabledMemberIds != base.disabledMemberIds) draft.disabledMemberIds else latest.disabledMemberIds,
    activationStrategyOverride = if (draft.activationStrategyOverride != base.activationStrategyOverride) draft.activationStrategyOverride else latest.activationStrategyOverride,
    autoChatRoundsOverride = if (draft.autoChatRoundsOverride != base.autoChatRoundsOverride) draft.autoChatRoundsOverride else latest.autoChatRoundsOverride,
    autoModeDelayOverride = if (draft.autoModeDelayOverride != base.autoModeDelayOverride) draft.autoModeDelayOverride else latest.autoModeDelayOverride,
    singleChatContextCountOverride = if (draft.singleChatContextCountOverride != base.singleChatContextCountOverride) draft.singleChatContextCountOverride else latest.singleChatContextCountOverride,
    systemPromptOverride = if (draft.systemPromptOverride != base.systemPromptOverride) draft.systemPromptOverride else latest.systemPromptOverride,
    autoSummaryEnabledOverride = if (draft.autoSummaryEnabledOverride != base.autoSummaryEnabledOverride) draft.autoSummaryEnabledOverride else latest.autoSummaryEnabledOverride,
    autoSummaryMessageThresholdOverride = if (draft.autoSummaryMessageThresholdOverride != base.autoSummaryMessageThresholdOverride) draft.autoSummaryMessageThresholdOverride else latest.autoSummaryMessageThresholdOverride,
    summaryKeepRecentMessagesOverride = if (draft.summaryKeepRecentMessagesOverride != base.summaryKeepRecentMessagesOverride) draft.summaryKeepRecentMessagesOverride else latest.summaryKeepRecentMessagesOverride,
)

internal fun mergeGroupDefaultsDraft(latest: GroupChatDefaults, base: GroupChatDefaults, draft: GroupChatDefaults): GroupChatDefaults = latest.copy(
    activationStrategy = if (draft.activationStrategy != base.activationStrategy) draft.activationStrategy else latest.activationStrategy,
    autoChatRounds = if (draft.autoChatRounds != base.autoChatRounds) draft.autoChatRounds else latest.autoChatRounds,
    autoModeDelay = if (draft.autoModeDelay != base.autoModeDelay) draft.autoModeDelay else latest.autoModeDelay,
    singleChatContextCount = if (draft.singleChatContextCount != base.singleChatContextCount) draft.singleChatContextCount else latest.singleChatContextCount,
    systemPrompt = if (draft.systemPrompt != base.systemPrompt) draft.systemPrompt else latest.systemPrompt,
    autoSummaryEnabled = if (draft.autoSummaryEnabled != base.autoSummaryEnabled) draft.autoSummaryEnabled else latest.autoSummaryEnabled,
    autoSummaryMessageThreshold = if (draft.autoSummaryMessageThreshold != base.autoSummaryMessageThreshold) draft.autoSummaryMessageThreshold else latest.autoSummaryMessageThreshold,
    summaryKeepRecentMessages = if (draft.summaryKeepRecentMessages != base.summaryKeepRecentMessages) draft.summaryKeepRecentMessages else latest.summaryKeepRecentMessages,
)

/** 群聊选人策略，具体调度由群聊执行层负责。 */
@Serializable
enum class GroupActivationStrategy {
    NATURAL,   // 根据上下文选择发言人
    LIST,      // 按名单顺序轮流
    MANUAL,    // 用户手动选择
    POOLED,    // 纯随机抽取，排除上一位发言人（仅一人时除外）
}
