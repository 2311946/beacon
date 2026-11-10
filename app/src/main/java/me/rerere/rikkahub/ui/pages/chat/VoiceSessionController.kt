package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.asr.ASRController
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueuePausedException

enum class VoicePhase { Off, Connecting, Listening, Transcribing, Waiting, Speaking, Error }

data class VoiceSessionState(
    val phase: VoicePhase = VoicePhase.Off,
    val transcript: String = "",
    val error: String? = null,
    val pendingReplies: Int = 0,
    val isMuted: Boolean = false,
    val amplitudes: List<Float> = emptyList(),
    val lastReply: String = "",
) {
    val isActive: Boolean get() = phase != VoicePhase.Off && phase != VoicePhase.Error
}

/** Capture and generation run independently. TTS and mute both close the microphone. */
class VoiceSessionController(
    private val scope: CoroutineScope,
    private val getString: (Int) -> String,
    private val enqueueMessage: (String) -> Deferred<String?>,
) {
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var captureJob: Job? = null
    private var captureVersion = 0L
    private var controls: Channel<Event>? = null
    var activeCallId: String? = null
        private set
    private var sessionToken: Any? = null
    private var endCall: (() -> Unit)? = null

    private fun clearCall(token: Any?) {
        if (token == null || sessionToken !== token) return
        sessionToken = null
        activeCallId = null
        val callback = endCall
        endCall = null
        callback?.invoke()
    }

    private sealed interface Event {
        data class Utterance(val text: String, val captureId: Long) : Event
        data class Reply(val text: String?) : Event
        data class Failed(val error: Exception, val captureId: Long? = null) : Event
        data object MuteChanged : Event
    }

    fun start(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
        stopSpeaking: () -> Unit,
        listenWhileReplying: Boolean = true,
        forCall: Boolean = false,
        onCallStateChanged: (String?) -> Unit = {},
    ) {
        if (job?.isCompleted == false) return
        val token = Any()
        sessionToken = token
        activeCallId = if (forCall) kotlin.uuid.Uuid.random().toString() else null
        endCall = if (forCall) ({ onCallStateChanged(null) }) else null
        if (forCall) onCallStateChanged(activeCallId)
        mutableState.value = VoiceSessionState(VoicePhase.Connecting)
        job = scope.launch {
            try {
                stopSpeaking()
                delay(200)
                runSession(createAsr, speak, listenWhileReplying)
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                mutableState.update {
                    it.copy(
                        phase = VoicePhase.Error,
                        amplitudes = emptyList(),
                        error = when (e) {
                            is TimeoutCancellationException -> "Speech recognition timed out. Restart voice mode."
                            is MessageQueuePausedException -> getString(R.string.chat_page_voice_queue_paused)
                            else -> e.message ?: getString(R.string.chat_page_voice_failed)
                        },
                    )
                }
            } finally {
                clearCall(token)
                stopSpeaking()
            }
        }.also { sessionJob ->
            // scope 已取消时协程可能尚未执行 try/finally，仍须解除通话状态。
            sessionJob.invokeOnCompletion { clearCall(token) }
        }
    }

    /** Discards the unfinished utterance, but never cancels messages accepted by the chat queue. */
    fun setMuted(muted: Boolean) {
        if (!state.value.isActive || state.value.isMuted == muted) return
        captureVersion++
        captureJob?.cancel()
        mutableState.update {
            it.copy(
                isMuted = muted,
                amplitudes = emptyList(),
                transcript = if (muted) "" else it.transcript,
                phase = if (it.phase == VoicePhase.Speaking) it.phase else VoicePhase.Waiting,
            )
        }
        controls?.trySend(Event.MuteChanged)
    }

    private suspend fun runSession(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
        listenWhileReplying: Boolean,
    ) = coroutineScope {
        val events = Channel<Event>(Channel.UNLIMITED)
        controls = events
        val submitted = Channel<Deferred<String?>>(Channel.UNLIMITED)
        val replies = ArrayDeque<String>()
        var asr: ASRController? = null
        var capture: Job? = null

        // Await replies in submission order, without blocking capture. Queue removal returns null.
        launch {
            try {
                for (reply in submitted) events.send(Event.Reply(reply.await()))
            } catch (e: Exception) {
                if (e is CancellationException && !isActive) throw e
                events.send(Event.Failed(e))
            }
        }

        try {
            while (isActive) {
                if (state.value.isMuted) {
                    capture?.cancelAndJoin()
                    capture = null
                    captureJob = null
                    asr = null
                }
                // Finish any sentence already in progress before giving TTS the microphone pause.
                if (speak != null && replies.isNotEmpty() && asr?.state?.value?.voiceTurn?.itemId == null) {
                    capture?.cancelAndJoin()
                    capture = null
                    captureJob = null
                    asr = null
                    val reply = replies.removeFirst()
                    mutableState.update {
                        it.copy(phase = VoicePhase.Speaking, lastReply = reply, amplitudes = emptyList())
                    }
                    speak(reply)
                    delay(300) // Let the loudspeaker's tail decay before opening the microphone.
                    continue
                }
                // Turn-based calls wait for the reply and complete playback before reopening capture.
                if (capture == null && (state.value.isMuted || (!listenWhileReplying && state.value.pendingReplies > 0))) {
                    mutableState.update { it.copy(phase = VoicePhase.Waiting, amplitudes = emptyList()) }
                } else if (capture == null) {
                    mutableState.update { it.copy(phase = VoicePhase.Connecting, transcript = "", amplitudes = emptyList()) }
                    val recorder = createAsr()
                    val captureId = ++captureVersion
                    asr = recorder
                    capture = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            events.send(Event.Utterance(listen(recorder), captureId))
                        } catch (e: Exception) {
                            if (e is CancellationException && !isActive) throw e
                            events.send(Event.Failed(e, captureId))
                        }
                    }
                    captureJob = capture
                }
                when (val event = events.receive()) {
                    is Event.Utterance -> {
                        if (event.captureId != captureVersion || state.value.isMuted) continue
                        capture?.join()
                        capture = null
                        captureJob = null
                        asr = null
                        if (event.text.isNotBlank()) {
                            val reply = enqueueMessage(event.text)
                            mutableState.update {
                                it.copy(transcript = event.text, amplitudes = emptyList(), pendingReplies = it.pendingReplies + 1)
                            }
                            submitted.send(reply)
                        }
                    }
                    is Event.Reply -> {
                        mutableState.update {
                            it.copy(
                                pendingReplies = (it.pendingReplies - 1).coerceAtLeast(0),
                                lastReply = event.text?.takeIf(String::isNotBlank) ?: it.lastReply,
                            )
                        }
                        event.text?.takeIf { speak != null && it.isNotBlank() }?.let { replies.addLast(it) }
                    }
                    is Event.Failed -> if (event.captureId == null || event.captureId == captureVersion) throw event.error
                    Event.MuteChanged -> {
                        capture?.cancelAndJoin()
                        capture = null
                        captureJob = null
                        asr = null
                    }
                }
            }
        } finally {
            controls = null
            captureJob = null
            capture?.cancelAndJoin()
            // Submitted messages belong to the chat queue; leaving voice mode only detaches observers.
            submitted.close()
            events.close()
        }
    }

    private suspend fun listen(asr: ASRController): String {
        try {
            asr.start {}
            withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    it.status != ASRStatus.Connecting
                }.also {
                    check(it.status == ASRStatus.Listening || it.voiceTurn.isComplete) { "Unable to start speech recognition" }
                }
            }
            val ended = withTimeout(120_000) {
                asr.state.onEach {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    check(it.status == ASRStatus.Listening || it.voiceTurn.isComplete) { "Speech recognition disconnected" }
                    mutableState.update { current ->
                        if (current.isMuted) current else current.copy(
                            phase = VoicePhase.Listening,
                            transcript = it.transcript,
                            amplitudes = it.amplitudes,
                        )
                    }
                }.first { it.voiceTurn.speechEnded }
            }
            asr.pauseCapture()
            mutableState.update { it.copy(phase = VoicePhase.Transcribing, transcript = ended.transcript, amplitudes = emptyList()) }
            return withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    check(it.voiceTurn.isComplete || it.status == ASRStatus.Listening) {
                        "Speech recognition disconnected before the final transcript was received"
                    }
                    it.voiceTurn.isComplete
                }.voiceTurn.finalText.orEmpty()
            }
        } finally {
            asr.dispose()
        }
    }

    fun stop() {
        clearCall(sessionToken)
        captureVersion++
        captureJob?.cancel()
        job?.cancel()
        mutableState.value = VoiceSessionState()
    }
}