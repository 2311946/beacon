package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WakeupNotificationTest {
    @Test fun limitsChinesePreviewToFiftyCharacters() {
        assertEquals("你".repeat(50), wakeupNotificationPreview("你".repeat(80)))
    }

    @Test fun doesNotSplitEmojiSurrogatePair() {
        assertEquals("你".repeat(49) + "😀", wakeupNotificationPreview("你".repeat(49) + "😀后续"))
    }

    @Test fun normalizesWhitespaceAndKeepsShortMessages() {
        assertEquals("你好 在吗", wakeupNotificationPreview("  你好\n\t在吗  "))
        assertEquals("", wakeupNotificationPreview("  \n "))
    }

    @Test fun dedicatedNotificationRecognizesWakeupAndTaskMessagesOnly() {
        val wakeup = UIMessagePart.Text("早安", buildJsonObject { put("beacon_event", "autonomous_wakeup") })
        val task = UIMessagePart.Text("执行任务", buildJsonObject { put("beacon_event", "autonomous_task") })
        val normal = UIMessagePart.Text("你好")
        assertTrue(hasDedicatedWakeupNotification(listOf(wakeup)))
        assertTrue(hasDedicatedWakeupNotification(listOf(task)))
        assertFalse(hasDedicatedWakeupNotification(listOf(normal)))
        assertFalse(hasDedicatedWakeupNotification(emptyList()))
        assertFalse(hasDedicatedWakeupNotification(listOf(
            UIMessagePart.Text("普通消息", buildJsonObject { put("beacon_event", "other") }),
        )))
    }

    @Test fun wakeupAndFollowingUserMessageKeepIndependentResults() = runBlocking {
        val queue = MessageQueue()
        val wakeupReply = CompletableDeferred<String?>()
        val userReply = CompletableDeferred<String?>()
        queue.enqueue(listOf(UIMessagePart.Text("唤醒", buildJsonObject {
            put("beacon_event", "autonomous_wakeup")
        })), reply = wakeupReply)
        queue.enqueue(listOf(UIMessagePart.Text("用户随后发言")), reply = userReply)
        val wakeup = requireNotNull(queue.takeNext())
        val user = requireNotNull(queue.takeNext())
        assertTrue(hasDedicatedWakeupNotification(wakeup.parts))
        assertFalse(hasDedicatedWakeupNotification(user.parts))
        wakeup.reply?.complete("早安")
        user.reply?.complete("普通回复")
        assertEquals("早安", wakeupReply.await())
        assertEquals("普通回复", userReply.await())
    }

    @Test fun chatNotificationUsesAssistantNameEvenWhenAvatarDisabled() {
        val assistant = Assistant(name = "何瑞延", useAssistantAvatar = false)
        assertEquals("何瑞延", chatNotificationSenderName(assistant, "AI 助手"))
        assertEquals("AI 助手", chatNotificationSenderName(Assistant(useAssistantAvatar = false), "AI 助手"))
    }

    @Test fun rolesUseIndependentStableNotificationTags() {
        assertEquals(wakeupNotificationTag("a"), wakeupNotificationTag("a"))
        assertNotEquals(wakeupNotificationTag("a"), wakeupNotificationTag("b"))
    }
}