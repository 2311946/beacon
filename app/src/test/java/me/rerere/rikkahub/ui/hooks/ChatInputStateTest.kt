package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ChatInputStateTest {
    @Test
    fun `named stickers travel with draft selection and message text`() {
        val state = ChatInputState()
        val url = "https://example.com/render?token=AbC&size=1"
        state.setMessageText("前文旧内容后文")
        state.textContent.edit { selection = androidx.compose.ui.text.TextRange(2, 5) }
        state.insertSticker(url, "委屈")
        assertEquals("前文\n[表情包:委屈] $url\n后文", state.textContent.text.toString())
        assertEquals(listOf(UIMessagePart.Text(state.textContent.text.toString())), state.getContents())
    }

    @Test
    fun `consecutive stickers keep separate URLs and unnamed legacy items remain usable`() {
        val state = ChatInputState()
        state.insertSticker("https://example.com/a.png", "开心")
        state.insertSticker("https://example.com/b.gif")
        assertEquals("[表情包:开心] https://example.com/a.png [表情包:未命名] https://example.com/b.gif ",
            state.textContent.text.toString())
    }

    private fun editing(parts: List<UIMessagePart>) = ChatInputState().apply {
        editingMessage = Uuid.random()
        setContents(parts)
    }

    @Test
    fun `reasoning and text message allows editing reasoning in place`() {
        val reasoning = UIMessagePart.Reasoning("old thought")
        val state = editing(listOf(reasoning, UIMessagePart.Text("answer")))

        assertNotNull(state.editingReasoning)
        assertEquals(emptyList<UIMessagePart>(), state.messageContent)

        state.editingReasoning!!.setTextAndPlaceCursorAtEnd("new thought")
        state.textContent.setTextAndPlaceCursorAtEnd("new answer")

        assertEquals(
            listOf(reasoning.copy(reasoning = "new thought"), UIMessagePart.Text("new answer")),
            state.getContents(),
        )
    }

    @Test
    fun `blank reasoning is dropped`() {
        val state = editing(listOf(UIMessagePart.Reasoning("old thought"), UIMessagePart.Text("answer")))

        state.editingReasoning!!.setTextAndPlaceCursorAtEnd("  ")

        assertEquals(listOf(UIMessagePart.Text("answer")), state.getContents())
    }

    @Test
    fun `signed reasoning is not editable and kept as is`() {
        val reasoning = UIMessagePart.Reasoning(
            reasoning = "thought",
            metadata = buildJsonObject { put("signature", JsonPrimitive("sig")) },
        )
        val state = editing(listOf(reasoning, UIMessagePart.Text("answer")))

        assertNull(state.editingReasoning)
        state.textContent.setTextAndPlaceCursorAtEnd("new answer")

        assertEquals(listOf(reasoning, UIMessagePart.Text("new answer")), state.getContents())
    }

    @Test
    fun `message with other parts is not editable`() {
        val state = editing(
            listOf(
                UIMessagePart.Reasoning("thought"),
                UIMessagePart.Text("answer"),
                UIMessagePart.Image("file:///image.png"),
            )
        )

        assertNull(state.editingReasoning)
    }

    @Test
    fun `clear input resets reasoning editing`() {
        val state = editing(listOf(UIMessagePart.Reasoning("thought"), UIMessagePart.Text("answer")))

        state.clearInput()

        assertNull(state.editingReasoning)
    }
}
