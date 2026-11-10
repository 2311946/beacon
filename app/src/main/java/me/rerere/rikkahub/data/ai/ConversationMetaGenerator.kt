package me.rerere.rikkahub.data.ai

import android.content.Context
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale

/** 用快速模型生成会话的标题和聊天建议，只负责请求模型，写回会话由调用方处理 */
class ConversationMetaGenerator(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
) {
    /** 群聊直接返回群名；单聊的快速模型或提供商找不到时抛出异常。 */
    suspend fun generateTitle(settings: Settings, conversation: Conversation): String {
        if (conversation.config?.isGroupChat == true) {
            return settings.groupChats.firstOrNull { it.conversationId == conversation.id }?.name
                ?: conversation.title
        }
        val model = settings.findModelById(settings.fastModelId)
            ?: throw IllegalStateException(context.getString(R.string.error_fast_model_not_found))
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException(context.getString(R.string.error_fast_model_provider_not_found))
        return generate(
            settings,
            model,
            provider,
            conversation,
            settings.titlePrompt,
            recentMessages = 4,
            UsageSource.TITLE
        )
            .trim()
    }

    /** 没有配置快速模型时返回 null */
    suspend fun generateSuggestions(settings: Settings, conversation: Conversation): List<String>? {
        if (conversation.config?.isGroupChat == true) return null
        val model = settings.findModelById(settings.fastModelId) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        return generate(
            settings,
            model,
            provider,
            conversation,
            settings.suggestionPrompt,
            recentMessages = 8,
            UsageSource.SUGGESTION,
        )
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(10)
    }

    private suspend fun generate(
        settings: Settings,
        model: Model,
        provider: ProviderSetting,
        conversation: Conversation,
        prompt: String,
        recentMessages: Int,
        source: UsageSource,
    ): String {
        val result = usageRecorder.wrap(
            providerManager.getProviderByType(provider),
            UsageTag(source, conversation.assistantId, conversation.id),
        ).generateText(
            providerSetting = provider,
            messages = listOf(
                UIMessage.user(
                    prompt.applyPlaceholders(
                        "locale" to Locale.getDefault().displayName,
                        "content" to conversation.currentMessages
                            .takeLast(recentMessages).joinToString("\n\n") { it.summaryAsText(maxLength = 500) },
                    )
                ),
            ),
            params = backgroundTextGenerationParams(model, conversation.id, settings.fastModelReasoningLevel),
        )
        return result.message.toText()
    }
}
