package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.prepareGroupRegeneration
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withSpeaker
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GroupChatTaskTest {
    @Test
    fun `regenerating a user turn removes old replies then produces two complete rounds`() = runBlocking<Unit> {
        val user = UIMessage.user("重新回答")
        val old = UIMessage.assistant("旧回复")
        val speakers = listOf(Uuid.random(), Uuid.random())
        var conversation = Conversation.ofId(Uuid.random(), messages = listOf(user.toMessageNode(), old.toMessageNode()))
            .copy(config = ConversationConfig(isGroupChat = true)).withSpeaker(old.id, speakers.first())
            .prepareGroupRegeneration(user.id)
        val calls = mutableListOf<Uuid>()
        GroupChatTask(this).runRounds(2, 0, select = { speakers }) { id ->
            val message = UIMessage.assistant("新回复 ${calls.size}")
            conversation = conversation.upsertMessage(Uuid.random(), message).withSpeaker(message.id, id)
            calls += id
            true
        }
        assertEquals(speakers + speakers, calls)
        assertEquals(5, conversation.currentMessages.size)
        assertEquals(user, conversation.currentMessages.first())
        assertFalse(conversation.currentMessages.any { it.id == old.id })
        assertFalse(conversation.config!!.speakerMap.containsKey(old.id))
        assertEquals(speakers + speakers, conversation.currentMessages.drop(1).map { conversation.config!!.speakerMap[it.id] })
    }

    @Test
    fun `one two and ten rounds reply once per participant in each round`() = runBlocking<Unit> {
        val speakers = listOf(Uuid.random(), Uuid.random())
        for (rounds in listOf(1, 2, 10)) {
            val task = GroupChatTask(this)
            val calls = mutableListOf<Uuid>()
            task.runRounds(rounds, 0, select = { speakers }) { calls += it; true }
            assertEquals(List(rounds) { speakers }.flatten(), calls)
        }
    }

    @Test
    fun `manual selection honors configured rounds and muted members are skipped`() = runBlocking<Unit> {
        val first = Uuid.random()
        val muted = Uuid.random()
        val calls = mutableListOf<Uuid>()
        GroupChatTask(this).runRounds(2, 0, select = { listOf(first, first, muted) },
            isEnabled = { it != muted }) { calls += it; true }
        assertEquals(listOf(first, first), calls)
    }

    @Test
    fun `failed generation stops remaining replies rather than issuing more requests`() = runBlocking<Unit> {
        val calls = mutableListOf<Uuid>()
        GroupChatTask(this).runRounds(2, 0, select = { listOf(Uuid.random(), Uuid.random()) }) {
            calls += it
            false
        }
        assertEquals(1, calls.size)
    }

    @Test
    fun `leaving page does not cancel stream and returning sees current content and speaker`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val app = CoroutineScope(coroutineContext + SupervisorJob())
            val page = CoroutineScope(coroutineContext + Job())
            try {
                val task = GroupChatTask(app)
                val speaker = Uuid.random()
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val content = StringBuilder()
                page.launch { task.state.collect {} }
                assertTrue(task.start {
                    setSpeaker(speaker)
                    content.append("第一段")
                    started.complete(Unit)
                    finish.await()
                    content.append("第二段")
                })
                started.await()
                page.cancel()
                assertTrue(task.state.value.isGenerating)
                assertEquals(speaker, task.state.value.currentSpeakerId)
                assertEquals("第一段", content.toString())
                val returned = CoroutineScope(coroutineContext + Job())
                try {
                    assertEquals(speaker, task.state.first().currentSpeakerId)
                    returned.launch { task.state.collect {} }
                    finish.complete(Unit)
                    task.state.first { !it.isGenerating }
                    assertEquals("第一段第二段", content.toString())
                } finally { returned.cancel() }
            } finally { page.cancel(); app.cancel() }
        }
    }

    @Test
    fun `all rounds continue after page scope is cancelled`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val app = CoroutineScope(coroutineContext + SupervisorJob())
            val page = CoroutineScope(coroutineContext + Job())
            try {
                val task = GroupChatTask(app)
                val first = CompletableDeferred<Unit>()
                val proceed = CompletableDeferred<Unit>()
                val speakers = listOf(Uuid.random(), Uuid.random(), Uuid.random())
                val generated = mutableListOf<Uuid>()
                page.launch(start = CoroutineStart.UNDISPATCHED) {
                    task.start {
                        runRounds(2, 0, select = { speakers }) { id ->
                            generated += id
                            if (generated.size == 1) { first.complete(Unit); proceed.await() }
                            true
                        }
                    }
                }
                first.await()
                page.cancel()
                proceed.complete(Unit)
                task.state.first { !it.isGenerating }
                assertEquals(speakers + speakers, generated)
            } finally { page.cancel(); app.cancel() }
        }
    }

    @Test
    fun `reentering cannot start duplicate generation`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val app = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val task = GroupChatTask(app)
                val finish = CompletableDeferred<Unit>()
                var calls = 0
                assertTrue(task.start { calls++; finish.await() })
                assertFalse(task.start { calls++ })
                finish.complete(Unit)
                task.state.first { !it.isGenerating }
                assertEquals(1, calls)
            } finally { app.cancel() }
        }
    }

    @Test
    fun `explicit stop waits for stream final persistence before becoming idle`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val app = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val task = GroupChatTask(app)
                val started = CompletableDeferred<Unit>()
                val saving = CompletableDeferred<Unit>()
                val saved = CompletableDeferred<Unit>()
                var stopped = 0
                assertTrue(task.start {
                    try { started.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { saving.complete(Unit); saved.await() } }
                })
                started.await()
                task.stop { stopped++ }
                saving.await()
                assertTrue(task.state.value.isGenerating)
                assertFalse(task.start {})
                task.stop { stopped++ }
                saved.complete(Unit)
                task.state.first { !it.isGenerating }
                assertEquals(1, stopped)
                assertNull(task.state.value.currentSpeakerId)
            } finally { app.cancel() }
        }
    }

    @Test
    fun `manual selection remains pending across page recreation`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val app = CoroutineScope(coroutineContext + SupervisorJob())
            val page = CoroutineScope(coroutineContext + Job())
            try {
                val task = GroupChatTask(app)
                val chosen = CompletableDeferred<Uuid?>()
                val speaker = Uuid.random()
                task.start { chosen.complete(awaitManualSpeaker()) }
                task.state.first { it.awaitingManualSpeaker }
                page.cancel()
                assertTrue(task.state.value.awaitingManualSpeaker)
                task.chooseManualSpeaker(speaker)
                assertEquals(speaker, chosen.await())
                task.state.first { !it.isGenerating }
            } finally { page.cancel(); app.cancel() }
        }
    }

    @Test
    fun `stopping manual selection does not leave a pending choice`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val app = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val task = GroupChatTask(app)
                task.start { awaitManualSpeaker() }
                task.state.first { it.awaitingManualSpeaker }
                task.stop()
                task.state.first { !it.isGenerating }
                assertFalse(task.state.value.awaitingManualSpeaker)
                task.chooseManualSpeaker(Uuid.random())
                assertTrue(task.start {})
                task.state.first { !it.isGenerating }
            } finally { app.cancel() }
        }
    }

    @Test
    fun `different groups keep independent generation and stop states`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val app = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val first = GroupChatTask(app)
                val second = GroupChatTask(app)
                val finish = CompletableDeferred<Unit>()
                first.start { awaitCancellation() }
                second.start { finish.await() }
                first.stop()
                first.state.first { !it.isGenerating }
                assertTrue(second.state.value.isGenerating)
                finish.complete(Unit)
                second.state.first { !it.isGenerating }
            } finally { app.cancel() }
        }
    }

    @Test
    fun `generation failure clears busy state and reports failure once`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val app = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val failures = mutableListOf<Exception>()
                val task = GroupChatTask(app) { failures += it }
                task.start { error("测试请求失败") }
                task.state.first { !it.isGenerating }
                assertEquals(1, failures.size)
                assertEquals("测试请求失败", failures.single().message)
                assertTrue(task.start {})
                task.state.first { !it.isGenerating }
            } finally { app.cancel() }
        }
    }

    @Test
    fun `cancelled application clears a task even when its body never starts`() = runBlocking<Unit> {
        val app = CoroutineScope(coroutineContext + SupervisorJob())
        app.cancel()
        val task = GroupChatTask(app)
        task.start { fail("Cancelled scope must not run request") }
        assertFalse(task.state.value.isGenerating)
    }
}