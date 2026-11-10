package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationUpsertMessageTest {
    private val user = UIMessage.user("question")
    private val answer = UIMessage.assistant("first answer")
    private val followUp = UIMessage.user("follow up")

    @Test
    fun `group user regeneration removes following replies all branches and stale mappings`() {
        val speaker = Uuid.random()
        val old = UIMessage.assistant("old branch")
        val current = UIMessage.assistant("selected branch")
        val earlier = UIMessage.assistant("earlier reply")
        val original = conversationOf(
            earlier.toMessageNode(), user.toMessageNode(),
            MessageNode(messages = listOf(old, current), selectIndex = 1),
            followUp.toMessageNode(), answer.toMessageNode(),
        ).copy(config = ConversationConfig(isGroupChat = true), chatSuggestions = listOf("stale"))
            .withSpeaker(earlier.id, speaker).withSpeaker(old.id, speaker)
            .withSpeaker(current.id, speaker).withSpeaker(answer.id, speaker)
        val result = original.prepareGroupRegeneration(user.id)
        assertEquals(listOf(earlier, user), result.currentMessages)
        assertEquals(original.messageNodes.take(2), result.messageNodes)
        assertEquals(mapOf(earlier.id to speaker), result.config?.speakerMap)
        assertEquals(emptyList<String>(), result.chatSuggestions)
        assertEquals(5, original.messageNodes.size)
    }

    @Test(expected = IllegalStateException::class)
    fun `group user regeneration rejects missing target without trimming history`() {
        conversationOf(user.toMessageNode(), answer.toMessageNode())
            .copy(config = ConversationConfig(isGroupChat = true))
            .prepareGroupRegeneration(Uuid.random())
    }

    @Test(expected = IllegalStateException::class)
    fun `group user regeneration rejects assistant target`() {
        conversationOf(answer.toMessageNode()).copy(config = ConversationConfig(isGroupChat = true))
            .prepareGroupRegeneration(answer.id)
    }

    @Test
    fun `reply to a missing node is appended as a new node`() {
        val nodeId = Uuid.random()
        val conversation = conversationOf(user.toMessageNode())

        val updated = conversation
            .upsertMessage(nodeId, UIMessage.assistant("partial"))
            .let { it.upsertMessage(nodeId, it.messageNodes.last().messages.single().copy(parts = UIMessage.assistant("full").parts)) }

        assertEquals(nodeId, updated.messageNodes.last().id)
        assertEquals(listOf("question", "full"), updated.currentMessages.map { it.toText() })
    }

    @Test
    fun `regenerated reply becomes a selected branch of its node`() {
        val answerNode = answer.toMessageNode()
        val conversation = conversationOf(user.toMessageNode(), answerNode, followUp.toMessageNode())

        val updated = conversation.upsertMessage(answerNode.id, UIMessage.assistant("second answer"))

        val node = updated.messageNodes[1]
        assertEquals(listOf("first answer", "second answer"), node.messages.map { it.toText() })
        assertEquals(1, node.selectIndex)
        assertEquals(followUp.id, updated.messageNodes[2].currentMessage.id)
    }

    @Test
    fun `nodes removed before the reply node do not shift where the reply is written`() {
        val answerNode = answer.toMessageNode()
        val conversation = conversationOf(user.toMessageNode(), answerNode)
            .let { it.copy(messageNodes = it.messageNodes.drop(1)) }

        val updated = conversation.upsertMessage(answerNode.id, answer.copy(parts = UIMessage.assistant("streamed").parts))

        assertEquals(1, updated.messageNodes.size)
        assertEquals(listOf("streamed"), updated.messageNodes.single().messages.map { it.toText() })
    }

    @Test
    fun `updating a reply keeps the branch the user switched to`() {
        val regenerated = UIMessage.assistant("second answer")
        val answerNode = MessageNode(messages = listOf(answer, regenerated), selectIndex = 0)
        val conversation = conversationOf(user.toMessageNode(), answerNode)

        val updated = conversation.upsertMessage(answerNode.id, regenerated.copy(parts = UIMessage.assistant("longer").parts))

        assertEquals(0, updated.messageNodes[1].selectIndex)
        assertEquals("longer", updated.messageNodes[1].messages[1].toText())
    }

    @Test
    fun `group regeneration targets original node after another assistant reply`() {
        val firstSpeaker = Uuid.random()
        val originalSpeaker = Uuid.random()
        val first = UIMessage.assistant("A的回复")
        val original = UIMessage.assistant("B的回复")
        val target = original.toMessageNode()
        val conversation = conversationOf(user.toMessageNode(), first.toMessageNode(), target)
            .copy(config = ConversationConfig(isGroupChat = true))
            .withSpeaker(first.id, firstSpeaker)
            .withSpeaker(original.id, originalSpeaker)
        // 即使调用方传错人，也必须使用原消息的角色。
        val locked = conversation.regenerationSpeaker(original.id, firstSpeaker)
        assertEquals(originalSpeaker, locked)
        val replacement = UIMessage.assistant("B的新回复")
        val updated = conversation.upsertMessage(
            conversation.replyNodeForTurn(target.id, locked), replacement,
        ).withSpeaker(replacement.id, locked!!)
        assertEquals(3, updated.messageNodes.size)
        assertEquals(first, updated.messageNodes[1].currentMessage)
        assertEquals(listOf(original, replacement), updated.messageNodes[2].messages)
        assertEquals(replacement.id, updated.messageNodes[2].currentMessage.id)
        assertEquals(originalSpeaker, updated.config?.speakerMap?.get(original.id))
        assertEquals(originalSpeaker, updated.config?.speakerMap?.get(replacement.id))
        assertEquals(originalSpeaker, updated.regenerationSpeaker(replacement.id, null))
    }

    @Test(expected = IllegalStateException::class)
    fun `missing group speaker mapping never falls back to caller role`() {
        val conversation = conversationOf(answer.toMessageNode())
            .copy(config = ConversationConfig(isGroupChat = true))
        conversation.regenerationSpeaker(answer.id, Uuid.random())
    }

    @Test
    fun `new group reply never uses previous assistant node`() {
        val node = answer.toMessageNode()
        val conversation = conversationOf(user.toMessageNode(), node)
        org.junit.Assert.assertNotEquals(node.id, conversation.replyNodeForTurn(null, Uuid.random()))
        assertEquals(node.id, conversation.replyNodeForTurn(null, null))
    }

    @Test
    fun `single chat regeneration does not require group speaker mapping`() {
        assertEquals(null, conversationOf(answer.toMessageNode()).regenerationSpeaker(answer.id, null))
    }

    @Test(expected = IllegalStateException::class)
    fun `deleted regeneration node cannot silently append a reply`() {
        conversationOf(user.toMessageNode()).replyNodeForTurn(Uuid.random(), Uuid.random())
    }

    private fun conversationOf(vararg nodes: MessageNode) =
        Conversation.ofId(Uuid.random(), messages = nodes.toList())
}
