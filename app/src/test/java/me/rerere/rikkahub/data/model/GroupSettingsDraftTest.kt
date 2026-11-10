package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GroupSettingsDraftTest {
    @Test fun `group avatar is saved only when its draft changes`() {
        val base = GroupChat(name = "群")
        val avatar = Avatar.Image("file:///group-avatar.png")
        val draft = base.copy(avatar = avatar)
        val latest = base.copy(conversationId = Uuid.random())
        val saved = mergeGroupSettingsDraft(latest, base, draft)
        assertEquals(Avatar.Dummy, base.avatar)
        assertEquals(avatar, saved.avatar)
        assertEquals(latest.conversationId, saved.conversationId)
        assertEquals(latest.copy(avatar = avatar), saved)
        assertEquals(saved, mergeGroupSettingsDraft(saved, base, base))
        assertEquals(Avatar.Dummy, mergeGroupSettingsDraft(saved, saved, saved.copy(avatar = Avatar.Dummy)).avatar)
    }

    @Test fun `group avatar persists and old groups default to no avatar`() {
        assertEquals(Avatar.Dummy, Json.decodeFromString<GroupChat>("{}").avatar)
        listOf(Avatar.Dummy, Avatar.Emoji("群"), Avatar.Image("file:///group-avatar.png")).forEach { avatar ->
            val group = GroupChat(name = "群", avatar = avatar)
            assertEquals(group, Json.decodeFromString<GroupChat>(Json.encodeToString(group)))
        }
    }

    @Test fun `editing a draft does not change saved group`() {
        val base = GroupChat(name = "旧名称")
        val draft = base.copy(name = "新名称", autoChatRoundsOverride = 4)
        assertEquals("旧名称", base.name)
        assertNull(base.autoChatRoundsOverride)
        val saved = mergeGroupSettingsDraft(base, base, draft)
        assertEquals("新名称", saved.name)
        assertEquals(4, saved.autoChatRoundsOverride)
    }

    @Test fun `save preserves live conversation model and unrelated settings`() {
        val base = GroupChat(name = "群")
        val latest = base.copy(conversationId = Uuid.random(), chatModelId = Uuid.random(), enabled = false,
            autoModeDelayOverride = 19)
        val saved = mergeGroupSettingsDraft(latest, base, base.copy(name = " 新群 "))
        assertEquals(latest.copy(name = "新群"), saved)
    }

    @Test fun `reset inheritance remains draft until committed`() {
        val base = GroupChat(name = "群", autoChatRoundsOverride = 8, singleChatContextCountOverride = 10)
        val draft = base.copy(autoChatRoundsOverride = null, singleChatContextCountOverride = null)
        assertEquals(8, base.autoChatRoundsOverride)
        val saved = mergeGroupSettingsDraft(base, base, draft)
        assertNull(saved.autoChatRoundsOverride)
        assertNull(saved.singleChatContextCountOverride)
        assertEquals(GroupChatDefaults(), saved.resolved(GroupChatDefaults()))
    }

    @Test fun `members and muted members commit together`() {
        val first = Uuid.random()
        val second = Uuid.random()
        val base = GroupChat(name = "群", memberIds = listOf(first), disabledMemberIds = listOf(first))
        val draft = base.copy(memberIds = listOf(second), disabledMemberIds = emptyList())
        assertEquals(draft, mergeGroupSettingsDraft(base, base, draft))
        assertEquals(listOf(first), base.memberIds)
    }

    @Test fun `global draft edits are isolated and preserve unrelated current fields`() {
        val base = GroupChatDefaults()
        val draft = base.copy(autoChatRounds = 6)
        val latest = base.copy(singleChatContextCount = 15)
        assertEquals(2, base.autoChatRounds)
        assertEquals(latest.copy(autoChatRounds = 6), mergeGroupDefaultsDraft(latest, base, draft))
    }

    @Test fun `unchanged and reverted drafts do not overwrite external edits`() {
        val base = GroupChat(name = "群")
        val latest = base.copy(name = "外部更新", autoChatRoundsOverride = 9)
        assertEquals(latest, mergeGroupSettingsDraft(latest, base, base))
        val defaults = GroupChatDefaults()
        val latestDefaults = defaults.copy(autoModeDelay = 22)
        assertEquals(latestDefaults, mergeGroupDefaultsDraft(latestDefaults, defaults, defaults))
    }
}
