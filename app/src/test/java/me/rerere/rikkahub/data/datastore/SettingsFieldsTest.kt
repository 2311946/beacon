package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.descriptors.elementNames
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantStatus
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.model.GroupChatDefaults
import me.rerere.rikkahub.data.model.AutonomousWakeupSettings
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.BuiltInChatTheme
import me.rerere.rikkahub.ui.theme.appearance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsFieldsTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `every settings property has exactly one field`() {
        val properties = Settings.serializer().descriptor.elementNames.sorted()

        assertEquals(properties, SETTINGS_FIELDS.map { it.property.name }.sorted())
    }

    @Test
    fun `settings survive a round trip through preferences`() {
        // 样本里和默认值相同的字段测不出读写是否对得上
        val defaults = emptyPreferences().toSettings()
        assertEquals(
            emptyList<String>(),
            SETTINGS_FIELDS.filterNot { it.changed(defaults, SAMPLE) }.map { it.property.name },
        )

        val preferences = mutablePreferencesOf().apply { putSettings(SAMPLE) }

        assertEquals(SAMPLE, preferences.toSettings())
    }

    @Test
    fun `assistant status preserves manual priority and restores auto after clearing`() {
        val key = id(20).toString()
        val preferences = mutablePreferencesOf().apply { putSettings(SAMPLE) }
        val restored = preferences.toSettings()
        assertEquals("想你了🥺", restored.assistantStatuses.getValue(key).displayText)
        val auto = restored.copy(assistantStatuses = restored.assistantStatuses +
            (key to restored.assistantStatuses.getValue(key).copy(manualText = null)))
        preferences.putSettings(auto, persisted = restored)
        assertEquals("刚说过早安☀️", preferences.toSettings().assistantStatuses.getValue(key).displayText)
        assertEquals(emptyMap<String, AssistantStatus>(), emptyPreferences().toSettings().assistantStatuses)
        val legacy = JsonInstant.decodeFromString<Settings>("""{"assistantId":"00000000-0000-0000-0000-000000000020"}""")
        assertEquals(emptyMap<String, AssistantStatus>(), legacy.assistantStatuses)
    }

    @Test
    fun `legacy and unknown theme selections fall back to classic`() {
        val legacy = JsonInstant.decodeFromString<DisplaySetting>("""{"userNickname":"existing"}""")
        assertEquals("classic", legacy.chatThemeId)
        assertEquals(BuiltInChatTheme.CLASSIC, BuiltInChatTheme.fromId("removed-theme"))
        assertEquals(null, BuiltInChatTheme.CLASSIC.appearance().colorScheme)
        assertEquals(emptyList<Color>(), BuiltInChatTheme.CLASSIC.appearance().userBubbleColors)
    }

    @Test
    fun `theme selection persists without changing classic preferences or assistant data`() {
        val original = SAMPLE.copy(dynamicColor = true)
        val selected = original.copy(displaySetting = original.displaySetting.copy(chatThemeId = "kuromi"))
        val preferences = mutablePreferencesOf().apply { putSettings(selected) }
        val restored = preferences.toSettings()
        assertEquals("kuromi", restored.displaySetting.chatThemeId)
        assertEquals(original.displaySetting, restored.displaySetting.copy(chatThemeId = original.displaySetting.chatThemeId))
        assertEquals(original, restored.copy(displaySetting = original.displaySetting))
        val classic = restored.copy(displaySetting = restored.displaySetting.copy(chatThemeId = "classic"))
        preferences.putSettings(classic, persisted = restored)
        assertEquals("classic", preferences.toSettings().displaySetting.chatThemeId)
        assertEquals(true, preferences.toSettings().dynamicColor)
    }

    @Test
    fun `kuromi appearance uses specified palette and rounded bubbles`() {
        val theme = BuiltInChatTheme.KUROMI.appearance()
        assertEquals(listOf(Color(0xFF2D1B4E), Color(0xFF4A2875)), theme.userBubbleColors)
        assertEquals(Color(0xFF1A1A2E), theme.assistantBubbleColor)
        assertEquals(Color(0xFFB388FF), theme.assistantBubbleBorder)
        assertEquals(Color(0xFF0D0D1A), theme.backgroundColor)
        assertEquals(Color(0xFF1E1E2E), theme.inputColor)
        assertEquals(Color.White, theme.textColor)
        assertEquals(Color(0xFFFFB6C1), theme.secondaryTextColor)
        assertEquals(16.dp, theme.bubbleCorner)
        assertEquals(true, theme.forceAssistantBubble)
        assertEquals(Color(0xFFB388FF), theme.colorScheme?.primary)
        assertEquals(Color(0xFFFF69B4), theme.colorScheme?.secondary)
    }

    @Test
    fun `legacy display settings fall back to global call voice`() {
        val restored = JsonInstant.decodeFromString<DisplaySetting>("""{"userNickname":"existing"}""")
        assertEquals(null, restored.voiceCallTtsProvider)
        assertEquals("existing", restored.userNickname)
    }

    @Test
    fun `independent call voice persists without changing global voice`() {
        val provider = me.rerere.tts.provider.TTSProviderSetting.OpenAI(voice = "nova")
        val settings = SAMPLE.copy(displaySetting = SAMPLE.displaySetting.copy(voiceCallTtsProvider = provider))
        val preferences = mutablePreferencesOf().apply { putSettings(settings) }
        val restored = preferences.toSettings()
        assertEquals(provider, restored.displaySetting.voiceCallTtsProvider)
        assertEquals(SAMPLE.ttsProviders, restored.ttsProviders)
        assertEquals(SAMPLE.selectedTTSProviderId, restored.selectedTTSProviderId)
        preferences.putSettings(restored.copy(displaySetting = restored.displaySetting.copy(voiceCallTtsProvider = null)), persisted = restored)
        assertEquals(null, preferences.toSettings().displaySetting.voiceCallTtsProvider)
    }

    // 这里的 key 名和取值就是用户盘上的格式，改动它们等于丢掉已有的设置
    @Test
    fun `stored format stays compatible with existing data`() {
        val stored = mutablePreferencesOf().apply { putSettings(SAMPLE) }.asMap().mapKeys { it.key.name }

        assertEquals(STORED_KEYS, stored.keys)
        assertEquals(false, stored["dynamic_color"])
        assertEquals(9090, stored["web_server_port"])
        assertEquals(1.5f, stored["default_tts_playback_speed"])
        assertEquals("title", stored["title_prompt"])
        assertEquals("00000000-0000-0000-0000-000000000001", stored["chat_model"])
        assertEquals("LOW", stored["fast_model_reasoning_level"])
        assertEquals("""[{"id":"00000000-0000-0000-0000-000000000007","name":"tag"}]""", stored["assistant_tags"])
        assertEquals("""{"resultSize":3}""", stored["search_common"])
    }

    @Test
    fun `missing keys read as first launch values`() {
        val settings = emptyPreferences().toSettings()

        assertEquals(DEFAULT_AUTO_MODEL_ID, settings.chatModelId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, settings.fastModelId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, settings.translateModeId)
        assertEquals(DEFAULT_MODE_INJECTIONS, settings.modeInjections)
        assertEquals(DEFAULT_ASSISTANT_ID, settings.assistantId)
        assertEquals(DEFAULT_SYSTEM_TTS_ID, settings.selectedTTSProviderId)
        assertEquals(null, settings.selectedASRProviderId)
        assertEquals(null, settings.memoryModelId)
        assertEquals(true, settings.dynamicColor)
        assertEquals(8080, settings.webServerPort)
    }

    @Test
    fun `unknown reasoning level reads as auto`() {
        val preferences = mutablePreferencesOf(SettingsStore.FAST_MODEL_REASONING_LEVEL to "REMOVED_LEVEL")

        assertEquals(ReasoningLevel.AUTO, preferences.toSettings().fastModelReasoningLevel)
    }

    @Test
    fun `clearing the asr provider removes its key`() {
        val preferences = mutablePreferencesOf().apply { putSettings(SAMPLE) }

        preferences.putSettings(SAMPLE.copy(selectedASRProviderId = null), persisted = SAMPLE)

        assertFalse(SettingsStore.SELECTED_ASR_PROVIDER in preferences)
        assertEquals(null, preferences.toSettings().selectedASRProviderId)
    }

    @Test
    fun `legacy group values migrate to overrides and reset survives serialization`() {
        val legacy = JsonInstant.decodeFromString<GroupChat>(
            """{"autoChatRounds":6,"autoModeDelay":8,"singleChatContextCount":9,"activationStrategy":"LIST"}"""
        )
        assertEquals(6, legacy.autoChatRoundsOverride)
        assertEquals(8, legacy.autoModeDelayOverride)
        val reset = legacy.copy(autoChatRoundsOverride = null, autoModeDelayOverride = null)
        val restored = JsonInstant.decodeFromString<GroupChat>(JsonInstant.encodeToString(reset))
        val defaults = GroupChatDefaults(autoChatRounds = 3, autoModeDelay = 4)
        assertEquals(3, restored.resolved(defaults).autoChatRounds)
        assertEquals(4, restored.resolved(defaults).autoModeDelay)
        assertEquals(9, restored.resolved(defaults).singleChatContextCount)
    }

    @Test
    fun `new group follows current defaults unless overridden`() {
        val group = GroupChat()
        assertEquals(7, group.resolved(GroupChatDefaults(autoChatRounds = 7)).autoChatRounds)
        assertEquals(2, group.copy(autoChatRoundsOverride = 2)
            .resolved(GroupChatDefaults(autoChatRounds = 7)).autoChatRounds)
    }

    @Test
    fun `only changed fields are written`() {
        val preferences = mutablePreferencesOf().apply { putSettings(SAMPLE) }
        // 盘上的值故意和已落盘的那份不一致：没变的字段如果也被重写，它就会变回去
        preferences[SettingsStore.TITLE_PROMPT] = "on disk"

        preferences.putSettings(SAMPLE.copy(themeId = "changed"), persisted = SAMPLE)

        assertEquals("changed", preferences[SettingsStore.THEME_ID])
        assertEquals("on disk", preferences[SettingsStore.TITLE_PROMPT])
    }
}

private fun id(n: Int) = Uuid.parse("00000000-0000-0000-0000-%012d".format(n))

// 每个字段都取了和默认值不同的值
private val SAMPLE = Settings(
    dynamicColor = false,
    themeId = "custom",
    customThemes = listOf(CustomTheme(id = "theme", name = "Theme")),
    developerMode = true,
    displaySetting = DisplaySetting(userNickname = "me"),
    networkSetting = NetworkSetting(userAgent = "agent"),
    favoriteModels = listOf(id(30)),
    chatModelId = id(1),
    fastModelId = id(2),
    fastModelReasoningLevel = ReasoningLevel.LOW,
    memoryModelId = id(6),
    memoryModelReasoningLevel = ReasoningLevel.HIGH,
    imageGenerationModelId = id(3),
    enableAutoTitle = false,
    titlePrompt = "title",
    translateModeId = id(4),
    translatePrompt = "translate",
    translateThinkingBudget = 1024,
    translateTargetLanguage = "ja",
    enableSuggestion = false,
    suggestionPrompt = "suggestion",
    ocrModelId = id(5),
    ocrPrompt = "ocr",
    assistantId = id(20),
    providers = listOf(ProviderSetting.OpenAI(id = id(31), name = "Provider")),
    assistants = listOf(Assistant(id = id(20), name = "Assistant")),
    assistantStatuses = mapOf(id(20).toString() to AssistantStatus("刚说过早安☀️", 1_000L, "想你了🥺")),
    groupChats = listOf(GroupChat(id = id(40), name = "Group", memberIds = listOf(id(20)))),
    groupChatDefaults = GroupChatDefaults(autoChatRounds = 4),
    autonomousWakeup = AutonomousWakeupSettings(enabled = true, assistantIds = setOf(id(20))),
    assistantTags = listOf(Tag(id = id(7), name = "tag")),
    searchServices = listOf(
        SearchServiceOptions.BingLocalOptions(id = id(21)),
        SearchServiceOptions.BingLocalOptions(id = id(22)),
    ),
    searchCommonOptions = SearchCommonOptions(resultSize = 3),
    searchServiceSelected = 1,
    mcpServers = listOf(McpServerConfig.SseTransportServer(id = id(8), url = "https://example.com/sse")),
    webDavConfig = WebDavConfig(url = "https://dav.example.com"),
    s3Config = S3Config(bucket = "backup"),
    uploadS3Config = S3Config(bucket = "upload"),
    mediaGenerationProviders = listOf(MediaGenerationProviderSetting.OpenAI(id = id(9))),
    chatImageGeneration = me.rerere.rikkahub.data.model.ChatImageGenerationConfig(automatic = true),
    ttsProviders = DEFAULT_TTS_PROVIDERS.take(1),
    selectedTTSProviderId = id(10),
    defaultTTSPlaybackSpeed = 1.5f,
    asrProviders = listOf(ASRProviderSetting.OpenAIRealtime(id = id(11))),
    selectedASRProviderId = id(11),
    modeInjections = listOf(PromptInjection.ModeInjection(id = id(14), name = "mode")),
    lorebooks = listOf(Lorebook(id = id(12), name = "lorebook")),
    quickMessages = listOf(QuickMessage(id = id(13), title = "hi")),
    webServerEnabled = true,
    webServerPort = 9090,
    webServerJwtEnabled = true,
    webServerAccessPassword = "secret",
    webServerLocalhostOnly = true,
    backupReminderConfig = BackupReminderConfig(enabled = true),
    sponsorAlertDismissedAt = 7,
)

private val STORED_KEYS = setOf(
    "dynamic_color",
    "theme_id",
    "custom_themes",
    "display_setting",
    "network_setting",
    "developer_mode",
    "favorite_models",
    "chat_model",
    "fast_model",
    "fast_model_reasoning_level",
    "memory_model",
    "memory_model_reasoning_level",
    "translate_model",
    "enable_suggestion",
    "enable_auto_title",
    "image_generation_model",
    "title_prompt",
    "translation_prompt",
    "translate_thinking_budget",
    "translate_target_language",
    "suggestion_prompt",
    "ocr_model",
    "ocr_prompt",
    "providers",
    "select_assistant",
    "assistants",
    "assistant_statuses",
    "assistant_tags",
    "group_chats",
    "group_chat_defaults",
    "autonomous_wakeup",
    "search_services",
    "search_common",
    "search_selected",
    "mcp_servers",
    "webdav_config",
    "s3_config",
    "upload_s3_config",
    "media_generation_providers",
    "chat_image_generation",
    "tts_providers",
    "selected_tts_provider",
    "default_tts_playback_speed",
    "asr_providers",
    "selected_asr_provider",
    "web_server_enabled",
    "web_server_port",
    "web_server_jwt_enabled",
    "web_server_access_password",
    "web_server_localhost_only",
    "mode_injections",
    "lorebooks",
    "quick_messages",
    "backup_reminder_config",
    "sponsor_alert_dismissed_at",
)
