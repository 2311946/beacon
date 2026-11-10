package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRController
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRVoiceTurn
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueue
import org.junit.Assert.*
import org.junit.Test

class VoiceSessionControllerTest {
    private class FakeAsr : ASRController {
        override val state = MutableStateFlow(ASRState())
        var disposed = false
        var paused = false
        override fun start(onTranscriptChange: (String) -> Unit) {
            state.value = ASRState(status = ASRStatus.Listening)
        }
        override fun pauseCapture() { paused = true }
        override fun stop() {}
        override fun dispose() { disposed = true }
        fun begin() {
            state.value = state.value.copy(voiceTurn = ASRVoiceTurn("a"))
        }
        fun end(text: String? = null) {
            state.value = state.value.copy(voiceTurn = ASRVoiceTurn("a", true, text))
        }
    }

    private class Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = MessageQueue()
        val asrs = mutableListOf<FakeAsr>()
        val replies = mutableListOf<CompletableDeferred<String?>>()
        val spoken = mutableListOf<String>()
        val playback = CompletableDeferred<Unit>()
        var stopCount = 0
        var failPlayback = false
        val voice = VoiceSessionController(scope, getString = { it.toString() }) { text ->
            assertTrue(asrs.last().disposed)
            CompletableDeferred<String?>().also {
                replies.add(it)
                queue.enqueue(listOf(UIMessagePart.Text(text)), reply = it)
            }
        }
        fun start(listenWhileReplying: Boolean = true) = voice.start(
            { FakeAsr().also { asrs.add(it) } },
            {
                assertTrue(asrs.all { asr -> asr.disposed })
                spoken.add(it)
                if (failPlayback) error("playback failed")
                playback.await()
            },
            { stopCount++ },
            listenWhileReplying,
        )
        suspend fun recorder(after: FakeAsr? = null): FakeAsr {
            awaitCondition {
                asrs.lastOrNull()?.let { it !== after && !it.disposed && it.state.value.status == ASRStatus.Listening } == true
            }
            return asrs.last()
        }
        fun close() { voice.stop(); scope.cancel() }
    }

    @Test fun `without TTS replies do not interrupt listening`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.voice.start(
                createAsr = { FakeAsr().also { rig.asrs.add(it) } },
                speak = null,
                stopSpeaking = {},
            )
            val first = rig.recorder()
            first.end("first")
            val second = rig.recorder(first)
            rig.replies.single().complete("reply")
            awaitCondition { rig.voice.state.value.pendingReplies == 0 }
            assertFalse(second.disposed)
            assertFalse(second.paused)
            assertEquals(VoicePhase.Listening, rig.voice.state.value.phase)
            second.end("second")
            rig.recorder(second)
            assertEquals(2, rig.replies.size)
        } finally { rig.close() }
    }

    @Test fun `new utterances enter queue while earlier reply is still generating`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("first")
            val second = rig.recorder(first)
            val generating = rig.queue.takeNext()!!
            assertFalse(generating.reply!!.isCompleted)
            second.end("second")
            val third = rig.recorder(second)
            third.end("third")
            rig.recorder(third)
            assertEquals(listOf("second", "third"), rig.queue.state.value.messages.map {
                (it.parts.single() as UIMessagePart.Text).text
            })
            assertEquals(3, rig.voice.state.value.pendingReplies)
            assertTrue(rig.spoken.isEmpty())
        } finally { rig.close() }
    }

    @Test fun `turn-based mode without TTS reopens the microphone only after the reply`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.voice.start(
                createAsr = { FakeAsr().also { rig.asrs.add(it) } },
                speak = null,
                stopSpeaking = {},
                listenWhileReplying = false,
            )
            val first = rig.recorder()
            first.end("first")
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Waiting }
            assertEquals(1, rig.asrs.size)
            assertTrue(first.disposed)
            rig.replies.single().complete("reply")
            rig.recorder(first)
            assertEquals(0, rig.voice.state.value.pendingReplies)
        } finally { rig.close() }
    }

    @Test fun `turn-based mode with TTS reopens the microphone only after playback`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start(listenWhileReplying = false)
            val first = rig.recorder()
            first.end("first")
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Waiting }
            rig.replies.single().complete("reply")
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Speaking }
            assertEquals(1, rig.asrs.size)
            rig.playback.complete(Unit)
            rig.recorder(first)
            assertEquals(listOf("reply"), rig.spoken)
        } finally { rig.close() }
    }

    @Test fun `turn-based mode resumes listening when the utterance is withdrawn`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start(listenWhileReplying = false)
            val first = rig.recorder()
            first.end("withdraw")
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Waiting }
            rig.queue.remove(rig.queue.state.value.messages.single().id)
            rig.recorder(first)
            assertTrue(rig.spoken.isEmpty())
        } finally { rig.close() }
    }

    @Test fun `speech stop waits for final text before enqueueing`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val asr = rig.recorder()
            asr.end()
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Transcribing }
            assertTrue(asr.paused)
            assertTrue(rig.replies.isEmpty())
            asr.end("final")
            rig.recorder(asr)
            assertEquals("final", (rig.queue.state.value.messages.single().parts.single() as UIMessagePart.Text).text)
            // Repeated/stale callbacks from a disposed recorder cannot enqueue another message.
            asr.end("duplicate")
            assertEquals(1, rig.replies.size)
        } finally { rig.close() }
    }

    @Test fun `reply during speech waits until current sentence is enqueued`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("first")
            val speaking = rig.recorder(first)
            speaking.begin()
            rig.replies.first().complete("reply one")
            awaitCondition { rig.voice.state.value.pendingReplies == 0 }
            assertFalse(speaking.disposed)
            assertTrue(rig.spoken.isEmpty())
            speaking.end()
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Transcribing }
            assertTrue(rig.spoken.isEmpty())
            speaking.end("second")
            awaitCondition { rig.spoken.size == 1 }
            assertEquals(2, rig.replies.size)
            assertTrue(speaking.disposed)
            assertEquals(VoicePhase.Speaking, rig.voice.state.value.phase)
            rig.playback.complete(Unit)
            rig.recorder(speaking)
        } finally { rig.close() }
    }

    @Test fun `replies are spoken in order and capture resumes only after playback`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("first")
            val second = rig.recorder(first)
            second.end("second")
            val idle = rig.recorder(second)
            rig.replies[1].complete("reply two")
            assertTrue(rig.spoken.isEmpty())
            rig.replies[0].complete("reply one")
            awaitCondition { rig.spoken.size == 1 }
            assertTrue(idle.disposed)
            assertEquals(listOf("reply one"), rig.spoken)
            assertEquals(VoicePhase.Speaking, rig.voice.state.value.phase)
            rig.playback.complete(Unit)
            awaitCondition { rig.spoken.size == 2 }
            assertEquals(listOf("reply one", "reply two"), rig.spoken)
            rig.recorder(idle)
        } finally { rig.close() }
    }

    @Test fun `withdrawing a queued utterance does not stop voice mode or speak it`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("first")
            val second = rig.recorder(first)
            second.end("withdraw")
            rig.recorder(second)
            val removed = rig.queue.state.value.messages.last()
            rig.queue.remove(removed.id)
            assertNull(rig.replies[1].await())
            rig.playback.complete(Unit)
            rig.replies[0].complete("reply one")
            awaitCondition { rig.voice.state.value.pendingReplies == 0 && rig.voice.state.value.phase == VoicePhase.Listening }
            assertEquals(listOf("reply one"), rig.spoken)
            assertTrue(rig.voice.state.value.isActive)
        } finally { rig.close() }
    }

    @Test fun `queue pause resolves its localized message`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("hello")
            rig.recorder(first)
            rig.queue.pause()
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Error }
            assertEquals(R.string.chat_page_voice_queue_paused.toString(), rig.voice.state.value.error)
        } finally { rig.close() }
    }

    @Test fun `empty utterance never enters queue`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val asr = rig.recorder()
            asr.end("")
            rig.recorder(asr)
            assertTrue(rig.replies.isEmpty())
        } finally { rig.close() }
    }

    @Test fun `ASR generation and playback errors stop capture and pause voice mode`() = runBlocking<Unit> {
        for (failure in listOf("asr", "generation", "playback")) {
            val rig = Rig()
            try {
                rig.start()
                val asr = rig.recorder()
                if (failure == "asr") {
                    asr.state.value = asr.state.value.copy(errorMessage = "offline")
                } else {
                    asr.end("hello")
                    rig.recorder(asr)
                    if (failure == "generation") rig.replies[0].completeExceptionally(IllegalStateException("failed"))
                    else {
                        rig.failPlayback = true
                        rig.replies[0].complete("reply")
                    }
                }
                awaitCondition { rig.voice.state.value.phase == VoicePhase.Error }
                if (failure == "asr") assertEquals("offline", rig.voice.state.value.error)
                assertTrue(rig.asrs.all { it.disposed })
            } finally { rig.close() }
        }
    }

    @Test fun `ending voice mode preserves accepted queued messages and detaches late replies`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("first")
            val second = rig.recorder(first)
            rig.voice.stop()
            awaitCondition { second.disposed }
            assertFalse(rig.replies.single().isCancelled)
            assertEquals(1, rig.queue.state.value.messages.size)
            rig.replies.single().complete("late reply")
            assertEquals(VoicePhase.Off, rig.voice.state.value.phase)
            assertTrue(rig.spoken.isEmpty())
        } finally { rig.close() }
    }

    @Test fun `ending during playback does not reopen the microphone`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val asr = rig.recorder()
            asr.end("hello")
            rig.recorder(asr)
            rig.replies.single().complete("reply")
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Speaking }
            val recorderCount = rig.asrs.size
            rig.voice.stop()
            awaitCondition { rig.stopCount == 2 }
            rig.playback.complete(Unit)
            assertEquals(VoicePhase.Off, rig.voice.state.value.phase)
            assertTrue(rig.asrs.all { it.disposed })
            assertEquals(recorderCount, rig.asrs.size)
        } finally { rig.close() }
    }

    @Test fun `mute discards unfinished speech and unmute creates a fresh recorder`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start(listenWhileReplying = false)
            val first = rig.recorder()
            first.begin()
            first.state.value = first.state.value.copy(transcript = "unfinished", amplitudes = listOf(0.2f, 0.8f))
            awaitCondition { rig.voice.state.value.amplitudes.isNotEmpty() }
            rig.voice.setMuted(true)
            awaitCondition { first.disposed }
            first.end("stale result")
            delay(30)
            assertTrue(rig.voice.state.value.isMuted)
            assertTrue(rig.voice.state.value.amplitudes.isEmpty())
            assertTrue(rig.replies.isEmpty())
            assertEquals(1, rig.asrs.size)
            rig.voice.setMuted(false)
            val second = rig.recorder(first)
            second.end("fresh result")
            awaitCondition { rig.replies.size == 1 }
            assertEquals("fresh result", (rig.queue.state.value.messages.single().parts.single() as UIMessagePart.Text).text)
        } finally { rig.close() }
    }

    @Test fun `mute during final transcription never submits its late result`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end()
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Transcribing }
            rig.voice.setMuted(true)
            first.end("late final")
            awaitCondition { first.disposed }
            assertTrue(rig.replies.isEmpty())
            rig.voice.setMuted(false)
            rig.recorder(first)
        } finally { rig.close() }
    }

    @Test fun `muted call still plays accepted reply but does not reopen capture`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start(listenWhileReplying = false)
            val first = rig.recorder()
            first.end("hello")
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Waiting }
            rig.voice.setMuted(true)
            rig.replies.single().complete("reply")
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Speaking }
            assertEquals("reply", rig.voice.state.value.lastReply)
            rig.playback.complete(Unit)
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Waiting }
            assertTrue(rig.voice.state.value.isMuted)
            assertEquals(1, rig.asrs.size)
            rig.voice.setMuted(false)
            rig.recorder(first)
        } finally { rig.close() }
    }

    @Test fun `hangup while muted ignores unmute and resets call state`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            rig.voice.setMuted(true)
            awaitCondition { first.disposed }
            rig.voice.stop()
            rig.voice.setMuted(false)
            delay(30)
            assertEquals(VoiceSessionState(), rig.voice.state.value)
            assertEquals(1, rig.asrs.size)
        } finally { rig.close() }
    }

    @Test fun `call creates id and clears it once immediately on hangup`() = runBlocking<Unit> {
        val rig = Rig()
        val changes = mutableListOf<String?>()
        try {
            rig.voice.start(
                createAsr = { FakeAsr().also { rig.asrs.add(it) } },
                speak = null,
                stopSpeaking = {},
                forCall = true,
                onCallStateChanged = { changes.add(it) },
            )
            val callId = rig.voice.activeCallId
            assertFalse(callId.isNullOrBlank())
            assertEquals(listOf(callId), changes)
            val recorder = rig.recorder()
            rig.voice.stop()
            assertNull(rig.voice.activeCallId)
            assertEquals(listOf(callId, null), changes)
            awaitCondition { recorder.disposed }
            rig.voice.stop()
            assertEquals(listOf(callId, null), changes)
        } finally { rig.close() }
    }

    @Test fun `call error and cancelled scope clear active call`() = runBlocking<Unit> {
        for (failure in listOf("asr", "scope")) {
            val rig = Rig()
            val changes = mutableListOf<String?>()
            try {
                rig.voice.start(
                    createAsr = { FakeAsr().also { rig.asrs.add(it) } },
                    speak = null,
                    stopSpeaking = {},
                    forCall = true,
                    onCallStateChanged = { changes.add(it) },
                )
                val recorder = rig.recorder()
                if (failure == "asr") recorder.state.value = recorder.state.value.copy(errorMessage = "offline")
                else rig.scope.cancel()
                awaitCondition { rig.voice.activeCallId == null && recorder.disposed }
                assertEquals(2, changes.size)
                assertNull(changes.last())
            } finally { rig.close() }
        }
    }

    @Test fun `ordinary voice mode does not publish call state`() = runBlocking<Unit> {
        val rig = Rig()
        val changes = mutableListOf<String?>()
        try {
            rig.voice.start(
                createAsr = { FakeAsr().also { rig.asrs.add(it) } },
                speak = null,
                stopSpeaking = {},
                onCallStateChanged = { changes.add(it) },
            )
            rig.recorder()
            assertNull(rig.voice.activeCallId)
            rig.voice.stop()
            assertTrue(changes.isEmpty())
        } finally { rig.close() }
    }

    companion object {
        private suspend fun awaitCondition(predicate: () -> Boolean) {
            withTimeout(3000) { while (!predicate()) delay(1) }
        }
    }
}
