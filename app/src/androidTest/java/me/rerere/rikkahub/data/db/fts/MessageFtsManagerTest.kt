package me.rerere.rikkahub.data.db.fts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class MessageFtsManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var fts: MessageFtsManager
    private val assistantId = Uuid.random()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "message-fts-test-").toFile()
        open()
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    private fun open() {
        database = AppDatabaseFactory.create(context, File(directory, "db").path)
        fts = MessageFtsManager(database)
    }

    @Test
    fun savingOnlyRewritesChangedMessages() = runBlocking {
        val apple = UIMessage.user("apple pie")
        val banana = UIMessage.assistant("banana split")
        val cherry = UIMessage.user("cherry tart")
        val conversation = conversation("first", apple, banana, cherry)
        save(conversation, insert = true)
        val before = indexedRowids()

        val durian = banana.copy(parts = UIMessage.assistant("durian cake").parts)
        val elderberry = UIMessage.assistant("elderberry jam")
        save(
            conversation.copy(
                title = "renamed",
                messageNodes = conversation.messageNodes.take(1) +
                    MessageNode.of(durian).copy(id = conversation.messageNodes[1].id) +
                    MessageNode.of(elderberry),
            ),
            insert = false,
        )
        val after = indexedRowids()

        assertEquals(before[apple.id.toString()], after[apple.id.toString()])
        assertNotEquals(before[banana.id.toString()], after[banana.id.toString()])
        assertEquals(setOf(apple.id, banana.id, elderberry.id).map { it.toString() }.toSet(), after.keys)
        assertRowidTableMatches()
        assertEquals(emptyList<String>(), fts.search("banana").map { it.messageId })
        assertEquals(emptyList<String>(), fts.search("cherry").map { it.messageId })
        val result = fts.search("durian").single()
        assertEquals(banana.id.toString(), result.messageId)
        assertEquals("renamed", result.title)
    }

    @Test
    fun deletingConversationKeepsOthers() = runBlocking {
        val kept = conversation("kept", UIMessage.user("shared word kept"))
        val deleted = conversation("deleted", UIMessage.user("shared word deleted"))
        save(kept, insert = true)
        save(deleted, insert = true)

        fts.deleteConversation(deleted.id.toString())

        assertEquals(listOf(kept.id.toString()), fts.search("shared").map { it.conversationId })
        assertRowidTableMatches()
    }

    @Test
    fun existingIndexGetsRowidTableOnOpen() = runBlocking {
        val first = conversation("first", UIMessage.user("legacy apple"))
        val second = conversation("second", UIMessage.user("legacy banana"))
        save(first, insert = true)
        save(second, insert = true)
        database.openHelper.writableDatabase.execSQL("DROP TABLE message_fts_rowid")
        database.close()

        open()
        assertRowidTableMatches()
        fts.deleteConversation(first.id.toString())
        assertEquals(listOf(second.id.toString()), fts.search("legacy").map { it.conversationId })
    }

    @Test
    fun assistantScopeFiltersBeforeResultLimit() = runBlocking {
        val otherAssistantId = Uuid.random()
        val other = conversation(
            "other assistant",
            *List(60) { UIMessage.user("shared keyword") }.toTypedArray(),
        ).copy(assistantId = otherAssistantId)
        // 正文相同且当前助手会话更旧，保证全库前50条都来自其他助手。
        val own = conversation("current assistant", UIMessage.user("shared keyword"))
            .copy(updateAt = java.time.Instant.EPOCH)
        save(other, insert = true)
        save(own, insert = true)

        val globalResults = fts.search("shared")
        assertEquals(50, globalResults.size)
        assertEquals(setOf(other.id.toString()), globalResults.map { it.conversationId }.toSet())
        val results = fts.search("shared", assistantIds = listOf(assistantId.toString()))

        assertEquals(listOf(own.id.toString()), results.map { it.conversationId })
        assertEquals(
            setOf(other.id.toString()),
            fts.search("shared", assistantIds = listOf(otherAssistantId.toString())).map { it.conversationId }.toSet(),
        )
    }

    @Test
    fun multipleAssistantScopeExcludesUnlistedAssistants() = runBlocking {
        val secondAssistantId = Uuid.random()
        val first = conversation("first", UIMessage.user("shared keyword first"))
        val second = conversation("second", UIMessage.user("shared keyword second"))
            .copy(assistantId = secondAssistantId)
        val excluded = conversation("excluded", UIMessage.user("shared keyword excluded"))
            .copy(assistantId = Uuid.random())
        listOf(first, second, excluded).forEach { save(it, insert = true) }

        assertEquals(
            setOf(first.id.toString(), second.id.toString()),
            fts.search("shared", assistantIds = listOf(assistantId.toString(), secondAssistantId.toString()))
                .map { it.conversationId }.toSet(),
        )
    }

    @Test
    fun emptyAssistantScopeDoesNotFallBackToGlobalSearch() = runBlocking {
        save(conversation("private", UIMessage.user("shared keyword private")), insert = true)

        assertEquals(1, fts.search("shared").size)
        assertEquals(emptyList<MessageSearchResult>(), fts.search("shared", assistantIds = emptyList()))
    }

    @Test
    fun exactConversationScopeFiltersBeforeResultLimitEvenForSameOwner() = runBlocking {
        val excluded = conversation(
            "同助手未授权会话",
            *List(60) { UIMessage.user("scoped keyword") }.toTypedArray(),
        )
        val allowed = conversation("精确授权会话", UIMessage.user("scoped keyword"))
            .copy(updateAt = java.time.Instant.EPOCH)
        save(excluded, insert = true)
        save(allowed, insert = true)

        val global = fts.search("scoped")
        assertEquals(50, global.size)
        assertEquals(setOf(excluded.id.toString()), global.map { it.conversationId }.toSet())
        val result = fts.search("scoped", conversationIds = listOf(allowed.id.toString()))
        assertEquals(listOf(allowed.id.toString()), result.map { it.conversationId })
        assertEquals(allowed.currentMessages.single().id.toString(), result.single().messageId)
    }

    @Test
    fun exactConversationScopeIncludesAuthorizedGroupWithAnotherOwnerOnly() = runBlocking {
        val own = conversation("私聊", UIMessage.user("scoped keyword"))
        val group = conversation("其他成员持有的群", UIMessage.user("scoped keyword"))
            .copy(assistantId = Uuid.random())
        val other = conversation("其他成员私聊", UIMessage.user("scoped keyword"))
            .copy(assistantId = group.assistantId)
        listOf(own, group, other).forEach { save(it, insert = true) }

        assertEquals(
            setOf(own.id.toString(), group.id.toString()),
            fts.search("scoped", conversationIds = listOf(own.id.toString(), group.id.toString()))
                .map { it.conversationId }.toSet(),
        )
        assertEquals(
            listOf(group.id.toString()),
            fts.search("scoped", conversationIds = listOf(group.id.toString())).map { it.conversationId },
        )
    }

    @Test
    fun emptyOrNonexistentExactConversationScopeNeverFallsBackToGlobal() = runBlocking {
        save(conversation("私聊", UIMessage.user("scoped keyword")), insert = true)

        assertEquals(1, fts.search("scoped").size)
        assertEquals(emptyList<MessageSearchResult>(), fts.search("scoped", conversationIds = emptyList()))
        assertEquals(
            emptyList<MessageSearchResult>(),
            fts.search("scoped", conversationIds = listOf(Uuid.random().toString())),
        )
    }

    @Test
    fun assistantAndExactConversationFiltersIntersect() = runBlocking {
        val other = conversation("其他助手", UIMessage.user("scoped keyword")).copy(assistantId = Uuid.random())
        save(other, insert = true)

        assertEquals(
            emptyList<MessageSearchResult>(),
            fts.search(
                "scoped",
                assistantIds = listOf(assistantId.toString()),
                conversationIds = listOf(other.id.toString()),
            ),
        )
    }

    @Test
    fun laterScopedPageFindsCurrentBranchAfterMoreThanFiftyOldBranchHits() = runBlocking {
        val oldBranches = List(60) { UIMessage.assistant("branch keyword") }
        val current = UIMessage.assistant("branch keyword")
        val node = MessageNode.of(current).copy(
            messages = oldBranches + current,
            selectIndex = oldBranches.size,
        )
        val scoped = conversation("分支分页").copy(messageNodes = listOf(node))
        save(scoped, insert = true)
        val scope = listOf(scoped.id.toString())
        val currentMessageIds = scoped.currentMessages.map { it.id.toString() }.toSet()

        val first = fts.search("branch", conversationIds = scope)
        assertEquals(50, first.size)
        assertTrue(first.all { it.messageId in oldBranches.map { message -> message.id.toString() } })
        assertTrue(first.none { it.messageId in currentMessageIds })

        val second = fts.search("branch", conversationIds = scope, limit = 50, offset = first.size)
        assertEquals(11, second.size)
        assertEquals(
            listOf(current.id.toString()),
            second.filter { it.messageId in currentMessageIds }.map { it.messageId },
        )
        assertEquals(61, (first + second).map { it.messageId }.toSet().size)
    }

    @Test
    fun scopedPagesHaveStableRowidOrderForAllSorts() = runBlocking {
        val scoped = conversation(
            "稳定分页",
            *List(63) { UIMessage.user("pagination keyword") }.toTypedArray(),
        )
        save(scoped, insert = true)
        val expected = indexedRowids().entries.sortedBy { it.value }.map { it.key }
        val scope = listOf(scoped.id.toString())

        MessageSearchSort.entries.forEach { sort ->
            val first = fts.search("pagination", sort = sort, conversationIds = scope, limit = 50, offset = 0)
            val second = fts.search("pagination", sort = sort, conversationIds = scope, limit = 50, offset = 50)
            assertEquals(expected.take(50), first.map { it.messageId })
            assertEquals(expected.drop(50), second.map { it.messageId })
            assertEquals(expected, (first + second).map { it.messageId })
            assertEquals(
                first,
                fts.search("pagination", sort = sort, conversationIds = scope, limit = 50, offset = 0),
            )
            assertEquals(
                emptyList<MessageSearchResult>(),
                fts.search("pagination", sort = sort, conversationIds = scope, limit = 50, offset = 63),
            )
        }
    }

    @Test
    fun pagedEmptyScopesAndZeroLimitNeverReturnGlobalResults() = runBlocking {
        val scoped = conversation("空范围分页", UIMessage.user("pagination keyword"))
        save(scoped, insert = true)

        assertEquals(1, fts.search("pagination").size)
        assertEquals(
            emptyList<MessageSearchResult>(),
            fts.search("pagination", conversationIds = emptyList(), limit = 10, offset = 0),
        )
        assertEquals(
            emptyList<MessageSearchResult>(),
            fts.search("pagination", conversationIds = emptyList(), limit = 10, offset = 1),
        )
        assertEquals(
            emptyList<MessageSearchResult>(),
            fts.search("pagination", assistantIds = emptyList(), limit = 10, offset = 0),
        )
        assertEquals(
            emptyList<MessageSearchResult>(),
            fts.search("pagination", conversationIds = listOf(Uuid.random().toString()), limit = 10, offset = 0),
        )
        assertEquals(
            emptyList<MessageSearchResult>(),
            fts.search("pagination", conversationIds = listOf(scoped.id.toString()), limit = 0, offset = 0),
        )
    }

    @Test
    fun negativePaginationArgumentsAreRejectedEvenForEmptyScopes() = runBlocking {
        listOf(-1 to 0, 50 to -1).forEach { (limit, offset) ->
            assertTrue(
                runCatching { fts.search("pagination", limit = limit, offset = offset) }
                    .exceptionOrNull() is IllegalArgumentException,
            )
            assertTrue(
                runCatching {
                    fts.search("pagination", conversationIds = emptyList(), limit = limit, offset = offset)
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }

    private fun conversation(title: String, vararg messages: UIMessage) = Conversation(
        assistantId = assistantId,
        title = title,
        messageNodes = messages.map { MessageNode.of(it) },
    )

    private suspend fun save(conversation: Conversation, insert: Boolean) {
        val entity = ConversationEntity(
            id = conversation.id.toString(),
            assistantId = conversation.assistantId.toString(),
            title = conversation.title,
            nodes = "[]",
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            chatSuggestions = "[]",
            isPinned = false,
        )
        if (insert) database.conversationDao().insert(entity) else database.conversationDao().update(entity)
        fts.indexConversation(conversation)
    }

    private fun indexedRowids(): Map<String, Long> =
        database.openHelper.writableDatabase.query("SELECT message_id, rowid FROM message_fts").use {
            buildMap { while (it.moveToNext()) put(it.getString(0), it.getLong(1)) }
        }

    private fun assertRowidTableMatches() {
        val query = "SELECT rowid, conversation_id FROM message_fts ORDER BY rowid"
        val mapped = "SELECT fts_rowid, conversation_id FROM message_fts_rowid ORDER BY fts_rowid"
        assertEquals(rows(query), rows(mapped))
    }

    private fun rows(sql: String): List<Pair<Long, String>> =
        database.openHelper.writableDatabase.query(sql).use {
            buildList { while (it.moveToNext()) add(it.getLong(0) to it.getString(1)) }
        }
}
