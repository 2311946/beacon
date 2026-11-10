package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.AssistantStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStatusLogicTest {
    @Test
    fun refreshesMissingAndExpiredStatusesOnly() {
        assertTrue(shouldRefreshAssistantStatus(null, 1_000_000L))
        assertTrue(shouldRefreshAssistantStatus(AssistantStatus("🙂嗨", 1_000L), 1_801_000L))
        assertFalse(shouldRefreshAssistantStatus(AssistantStatus("🙂嗨", 1_000L), 1_800_999L))
        assertTrue(shouldRefreshAssistantStatus(AssistantStatus("🙂嗨", 10_000L), 1_000L))
        assertFalse(shouldRefreshAssistantStatus(AssistantStatus("🙂嗨", 1L, "忙碌"), 9_000_000L, force = true))
    }

    @Test
    fun emptyManualStatusReturnsToAutoAndInvalidatesInflightGeneration() {
        var now = 0L
        val state = AssistantStatusRequestState { now }
        val oldRevision = state.revision()
        state.invalidate()
        assertFalse(state.isCurrent(oldRevision))
        val currentRevision = state.revision()
        state.failed(currentRevision)
        assertTrue(state.isThrottled())
        assertEquals(currentRevision + 1, state.invalidate(clearFailure = true))
        assertFalse(state.isThrottled())
        now = 61_000_000_000L
        assertFalse(state.isThrottled())
    }

    @Test
    fun wakeupAndManualResetCanStartAfterInvalidatingOlderRequest() {
        val state = AssistantStatusRequestState()
        val listRevision = state.startGeneration()
        val wakeupRevision = state.invalidate()
        assertFalse(state.isCurrent(listRevision))
        assertTrue(state.isCurrent(wakeupRevision))
        val generationRevision = state.startGeneration()
        assertTrue(state.isCurrent(generationRevision))
        assertFalse(state.isCurrent(wakeupRevision))
        val manualResetRevision = state.invalidate(clearFailure = true)
        assertTrue(state.isCurrent(manualResetRevision))
        assertFalse(state.isCurrent(generationRevision))
    }

    @Test
    fun cleansThinkingQuotesAndMultilineWithoutSplittingEmoji() {
        assertEquals("你好👨‍👩‍👧‍👦明天", cleanAssistantStatus("<think>略过</think>\n\"你好👨‍👩‍👧‍👦明天\""))
        assertEquals("在工作🇺🇸🙂非常忙碌心", cleanAssistantStatus("在工作🇺🇸🙂非常忙碌心情很好"))
        assertEquals("开心👍🏽的一天会很快乐", cleanAssistantStatus(" 开心👍🏽的一天会很快乐明天\n别读我"))
    }

    @Test
    fun rejectsStatusesWithoutEmojiOrUsefulText() {
        assertNull(cleanAssistantStatus("忙碌的一天"))
        assertNull(cleanAssistantStatus("<think>隐藏</think>\n"))
        assertNull(cleanAssistantStatus("🙂🙂🙂"))
    }

    @Test
    fun displayStatusMustNotMasqueradeAsWakeupExecution() {
        listOf("决定联系🙂", "已发送💬", "send_message🙂", "do_task🔧", "sleep💤").forEach {
            assertNull(cleanAssistantStatus(it))
        }
        assertEquals("想你了💭", cleanAssistantStatus("想你了💭"))
    }

    @Test
    fun codePointTruncationDoesNotSplitSurrogatePairs() {
        assertEquals("🙂🙂", "🙂🙂🙂".takeStatusCodePoints(2))
        assertTrue(statusGraphemes("🇺🇸👨‍👩‍👧‍👦👍🏽").size == 3)
    }
}
