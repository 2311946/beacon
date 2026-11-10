package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationConfigTest {
    private val modelA = Model(modelId = "model-a", displayName = "Model A", tools = setOf(BuiltInTools.Search))
    private val modelB = Model(modelId = "model-b", displayName = "Model B")
    private val mcpServer = Uuid.random()
    private val workspace = Uuid.random()
    private val injection = Uuid.random()
    private val lorebook = Uuid.random()

    private val assistant = Assistant(
        chatModelId = modelA.id,
        reasoningLevel = ReasoningLevel.HIGH,
        enableWebSearch = true,
        mcpServers = setOf(mcpServer),
        workspaceId = workspace,
        enabledSkills = setOf("skill"),
        modeInjectionIds = setOf(injection),
        lorebookIds = setOf(lorebook),
    )

    private fun settings(assistant: Assistant = this.assistant) = Settings(
        assistantId = assistant.id,
        assistants = listOf(assistant),
        chatModelId = modelB.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA, modelB))),
        mcpServers = listOf(McpServerConfig.StreamableHTTPServer(id = mcpServer)),
        modeInjections = listOf(PromptInjection.ModeInjection(id = injection)),
        lorebooks = listOf(Lorebook(id = lorebook)),
    )

    private fun conversation(assistant: Assistant = this.assistant) = Conversation(
        assistantId = assistant.id,
        messageNodes = emptyList(),
    )

    @Test
    fun `status visibility defaults to enabled for new and old configs`() {
        assertTrue(conversation().showAssistantStatus)
        assertTrue(ConversationConfig().showAssistantStatus)
        assertTrue(JsonInstant.decodeFromString<ConversationConfig>("{}").showAssistantStatus)
    }

    @Test
    fun `disabled status visibility survives serialization`() {
        val config = ConversationConfig(chatModelId = modelA.id, showAssistantStatus = false)
        val restored = JsonInstant.decodeFromString<ConversationConfig>(JsonInstant.encodeToString(config))
        assertEquals(config, restored)
        assertFalse(restored.showAssistantStatus)
    }

    @Test
    fun `draft visibility does not bind model configuration early`() {
        val draft = conversation().withAssistantStatusVisibility(false)
        assertNull(draft.config)
        assertFalse(draft.showAssistantStatus)
        val changed = settings(assistant.copy(chatModelId = modelB.id))
        assertEquals(modelB.id, changed.getChatModelOf(draft)?.id)
        val bound = draft.bindConfig(changed)
        assertFalse(bound.showAssistantStatus)
        assertEquals(modelB.id, bound.config?.chatModelId)
    }

    @Test
    fun `visibility is isolated to the selected conversation`() {
        val first = conversation().bindConfig(settings())
        val second = conversation().bindConfig(settings())
        val hidden = first.withAssistantStatusVisibility(false)
        assertFalse(hidden.showAssistantStatus)
        assertTrue(first.showAssistantStatus)
        assertTrue(second.showAssistantStatus)
        assertEquals(first.config?.copy(showAssistantStatus = false), hidden.config)
        assertEquals(first.messageNodes, hidden.messageNodes)
    }

    @Test
    fun `assistant updates and model switches preserve hidden status`() {
        val hidden = conversation().bindConfig(settings()).withAssistantStatusVisibility(false)
        val updated = settings().getAssistantOf(hidden).copy(chatModelId = modelB.id)
        val switched = hidden.withAssistantUpdate(updated, settings())
        assertFalse(switched.showAssistantStatus)
        assertTrue(switched.withAssistantStatusVisibility(true).showAssistantStatus)
    }

    @Test
    fun `assistant edits preserve group identity all speakers and message nodes`() {
        val message = me.rerere.ai.ui.UIMessage.assistant("群内回复")
        val group = conversation().bindConfig(settings()).copy(
            messageNodes = listOf(message.toMessageNode()),
            config = ConversationConfig(
                isGroupChat = true, speakerMap = mapOf(message.id to assistant.id), showAssistantStatus = false,
            ),
        )
        val edited = group.withAssistantUpdate(assistant.copy(chatModelId = modelB.id), settings())
        assertEquals(group.id, edited.id)
        assertSame(group.messageNodes, edited.messageNodes)
        assertTrue(edited.config!!.isGroupChat)
        assertEquals(group.config!!.speakerMap, edited.config!!.speakerMap)
        assertFalse(edited.showAssistantStatus)
        assertEquals(modelB.id, edited.config!!.chatModelId)
    }

    @Test
    fun `binding repairs only explicitly linked group without recreating history`() {
        val linked = conversation().bindConfig(settings())
        val unrelated = conversation().bindConfig(settings())
        val withGroup = settings().copy(groupChats = listOf(GroupChat(conversationId = linked.id)))
        val repaired = linked.bindConfig(withGroup)
        assertEquals(linked.id, repaired.id)
        assertSame(linked.messageNodes, repaired.messageNodes)
        assertTrue(repaired.config!!.isGroupChat)
        assertFalse(unrelated.bindConfig(withGroup).config!!.isGroupChat)
    }

    @Test
    fun `unstarted conversation follows the assistant`() {
        val conversation = conversation()

        assertSame(assistant, settings().getAssistantOf(conversation))
        assertEquals(modelA, settings().getChatModelOf(conversation))
    }

    @Test
    fun `binding snapshots the assistant configuration`() {
        val bound = conversation().bindConfig(settings())

        assertEquals(
            ConversationConfig(
                chatModelId = modelA.id,
                reasoningLevel = ReasoningLevel.HIGH,
                enableWebSearch = true,
                builtInSearch = true,
                mcpServers = setOf(mcpServer),
                workspaceId = workspace,
                enabledSkills = setOf("skill"),
            ),
            bound.config,
        )
        assertEquals(setOf(injection), bound.modeInjectionIds)
        assertEquals(setOf(lorebook), bound.lorebookIds)
        assertSame(bound, bound.bindConfig(settings(assistant.copy(reasoningLevel = ReasoningLevel.OFF))))
    }

    @Test
    fun `binding resolves the global default model`() {
        val assistant = assistant.copy(chatModelId = null)

        val bound = conversation(assistant).bindConfig(settings(assistant))

        assertEquals(modelB.id, bound.config?.chatModelId)
        assertEquals(false, bound.config?.builtInSearch)
    }

    @Test
    fun `bound conversation ignores later assistant changes`() {
        val bound = conversation().bindConfig(settings())
        val changed = assistant.copy(
            chatModelId = modelB.id,
            reasoningLevel = ReasoningLevel.OFF,
            enableWebSearch = false,
            mcpServers = emptySet(),
            workspaceId = null,
            enabledSkills = emptySet(),
            modeInjectionIds = emptySet(),
            lorebookIds = emptySet(),
            systemPrompt = "changed",
        )

        val resolved = settings(changed).getAssistantOf(bound)

        assertEquals(assistant.copy(systemPrompt = "changed"), resolved)
        assertEquals(modelA, settings(changed).getChatModelOf(bound))
    }

    @Test
    fun `bound built-in search overrides the model switch`() {
        val bound = conversation().bindConfig(settings())
        val disabled = bound.copy(config = bound.config?.copy(builtInSearch = false))
        val withoutModelSearch = settings().copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA.copy(tools = emptySet()), modelB))),
        )

        assertFalse(BuiltInTools.Search in settings().getChatModelOf(disabled)!!.tools)
        assertTrue(BuiltInTools.Search in withoutModelSearch.getChatModelOf(bound)!!.tools)
    }

    @Test
    fun `bound conversation falls back to the assistant model when its model is removed`() {
        val bound = conversation().bindConfig(settings())
        val withoutModelA = settings(assistant.copy(chatModelId = null)).copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelB))),
        )

        assertEquals(modelB.id, withoutModelA.getChatModelOf(bound)?.id)
        // 会话上固定的内置搜索开关属于被删掉的模型，不能套到退回的模型上
        assertEquals(modelB, withoutModelA.getChatModelOf(bound))
    }

    @Test
    fun `binding again pins the assistant model once the pinned model is removed`() {
        val bound = conversation().bindConfig(settings())
        val withoutModelA = settings(assistant.copy(chatModelId = null)).copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelB))),
        )

        val rebound = bound.bindConfig(withoutModelA)

        assertEquals(bound.config?.copy(chatModelId = modelB.id, builtInSearch = false), rebound.config)
        assertEquals(bound.modeInjectionIds, rebound.modeInjectionIds)
        assertSame(rebound, rebound.bindConfig(withoutModelA))
    }

    @Test
    fun `bound conversation drops references to removed items`() {
        val bound = conversation().bindConfig(settings())
        val emptied = settings().copy(mcpServers = emptyList(), modeInjections = emptyList(), lorebooks = emptyList())

        val resolved = emptied.getAssistantOf(bound)

        assertEquals(emptySet<Uuid>(), resolved.mcpServers)
        assertEquals(emptySet<Uuid>(), resolved.modeInjectionIds)
        assertEquals(emptySet<Uuid>(), resolved.lorebookIds)
    }

    @Test
    fun `chat page update goes to the assistant before the conversation starts`() {
        val conversation = conversation()
        val updated = assistant.copy(reasoningLevel = ReasoningLevel.LOW)

        assertSame(conversation, conversation.withAssistantUpdate(updated, settings()))
        assertSame(updated, updated.withoutConversationFields(conversation, assistant))
    }

    @Test
    fun `chat page update stays on the started conversation`() {
        val bound = conversation().bindConfig(settings())
        val newInjection = Uuid.random()
        val updated = settings().getAssistantOf(bound).copy(
            reasoningLevel = ReasoningLevel.LOW,
            modeInjectionIds = setOf(newInjection),
            quickMessageIds = setOf(Uuid.random()),
        )

        val conversation = bound.withAssistantUpdate(updated, settings())
        val stored = updated.withoutConversationFields(bound, assistant)

        assertEquals(ReasoningLevel.LOW, conversation.config?.reasoningLevel)
        assertEquals(setOf(newInjection), conversation.modeInjectionIds)
        // 不随会话固定的字段仍然写回助手
        assertEquals(assistant.copy(quickMessageIds = updated.quickMessageIds), stored)
    }

    @Test
    fun `switching model follows the new model's built-in search`() {
        val bound = conversation().bindConfig(settings())
        val updated = settings().getAssistantOf(bound).copy(chatModelId = modelB.id)

        val conversation = bound.withAssistantUpdate(updated, settings())

        assertEquals(modelB.id, conversation.config?.chatModelId)
        assertEquals(false, conversation.config?.builtInSearch)
    }

    @Test
    fun `switching workspace resets the working directory`() {
        val bound = conversation().bindConfig(settings()).copy(workspaceCwd = "/workspace/project")
        val assistantView = settings().getAssistantOf(bound)

        val sameWorkspace = bound.withAssistantUpdate(assistantView.copy(reasoningLevel = ReasoningLevel.LOW), settings())
        val otherWorkspace = bound.withAssistantUpdate(assistantView.copy(workspaceId = Uuid.random()), settings())

        assertEquals("/workspace/project", sameWorkspace.workspaceCwd)
        assertNull(otherWorkspace.workspaceCwd)
    }

    @Test
    fun `conversation bound before per-conversation config keeps its own injections`() {
        val conversationInjection = Uuid.random()
        val conversation = conversation().copy(modeInjectionIds = setOf(conversationInjection))

        val bound = conversation.bindConfig(settings())

        // 会话自己绑定过的保留，没绑定过的继承助手
        assertEquals(setOf(conversationInjection), bound.modeInjectionIds)
        assertEquals(setOf(lorebook), bound.lorebookIds)
    }
}
