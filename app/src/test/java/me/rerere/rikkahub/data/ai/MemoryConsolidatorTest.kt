package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.GroupChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryConsolidatorTest {
    private val zone = TimeZone.UTC

    private fun minute(minute: Int) = LocalDateTime(2026, 1, 1, 10, minute)
    private fun timeOf(minute: Int) = minute(minute).toInstant(zone).toEpochMilliseconds()

    private fun user(minute: Int, text: String = "user $minute") =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)), createdAt = minute(minute))

    private fun assistant(minute: Int, vararg parts: UIMessagePart = arrayOf(UIMessagePart.Text("reply $minute"))) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList(), createdAt = minute(minute))

    private fun tool(name: String, input: String = "{}", output: String? = "{}") = UIMessagePart.Tool(
        toolCallId = name,
        toolName = name,
        input = input,
        output = listOfNotNull(output?.let { UIMessagePart.Text(it) }),
    )

    private fun pending(messages: List<UIMessage>, consolidatedAt: Long, updatedAt: Long = 0) =
        pendingMemoryTurns(messages, updatedAt = updatedAt, consolidatedAt = consolidatedAt, timeZone = zone)

    @Test
    fun `only turns whose user message is newer than the mark are pending`() {
        val messages = listOf(user(1), assistant(2), user(3), assistant(4), user(5), assistant(6))

        val result = pending(messages, consolidatedAt = timeOf(2), updatedAt = timeOf(7))

        assertEquals(listOf(2, 4), result.turns.map { it.position })
        assertEquals(listOf("user 3", "reply 4"), result.turns.first().messages.map { it.value.toText() })
        assertEquals(timeOf(7), result.consolidatedThrough)
    }

    @Test
    fun `a regenerated reply does not make its turn pending again`() {
        val messages = listOf(user(1), assistant(9))

        val result = pending(messages, consolidatedAt = timeOf(5))

        assertTrue(result.turns.isEmpty())
        assertEquals(timeOf(9), result.consolidatedThrough)
    }

    @Test
    fun `an unfinished last turn is left for next time`() {
        val waitingForApproval = assistant(6, tool("workspace_shell", output = null))

        listOf(
            listOf(user(1), assistant(2), user(3), assistant(4), user(5)),
            listOf(user(1), assistant(2), user(3), assistant(4), user(5), waitingForApproval),
        ).forEach { messages ->
            val result = pending(messages, consolidatedAt = timeOf(2), updatedAt = timeOf(7))

            assertEquals(listOf(2), result.turns.map { it.position })
            // 进度停在没结束的那一轮前面，它下次还会被找出来
            assertEquals(timeOf(5) - 1, result.consolidatedThrough)
        }
    }

    @Test
    fun `same timestamp completed group turns wait until unfinished peer finishes`() {
        val first = assistant(3)
        val waiting = assistant(3, tool("waiting", output = null))
        val blocked = pendingMemoryTurns(
            listOf(first, waiting), timeOf(4), timeOf(3) - 1, zone, groupChat = true,
        )
        assertTrue(blocked.turns.isEmpty())
        assertNull(blocked.consolidatedThrough)
        val completed = waiting.copy(parts = listOf(UIMessagePart.Text("finished")))
        val ready = pendingMemoryTurns(
            listOf(first, completed), timeOf(4), timeOf(3) - 1, zone, groupChat = true,
        )
        assertEquals(listOf(first.id, completed.id), ready.turns.map { it.userMessage.id })
    }

    @Test
    fun `nothing advances while the only new turn is unfinished`() {
        val result = pending(listOf(user(1), assistant(2), user(3)), consolidatedAt = timeOf(3) - 1)

        assertTrue(result.turns.isEmpty())
        assertNull(result.consolidatedThrough)
    }

    @Test
    fun `turns without user text and compression checkpoints are skipped`() {
        val imageOnly = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image(url = "file:///a.png")),
            createdAt = minute(1),
        )
        val checkpoint = user(3, "summary of earlier messages").copy(isContextCheckpoint = true)
        val messages = listOf(imageOnly, assistant(2), checkpoint, user(4), assistant(5))

        val result = pending(messages, consolidatedAt = 0)

        assertEquals(listOf(3), result.turns.map { it.position })
        assertEquals(listOf("user 4", "reply 5"), result.turns.single().messages.map { it.value.toText() })
    }

    // 15 轮对话，第 n 轮的用户消息在第 2n-1 分钟
    private val longChat = (1..15).flatMap { turn -> listOf(user(turn * 2 - 1), assistant(turn * 2)) }

    @Test
    fun `a backlog larger than one batch is worked through oldest first without skipping turns`() {
        val first = pending(longChat, consolidatedAt = 0, updatedAt = timeOf(31))

        assertEquals(15, first.totalTurns)
        assertEquals((0 until 12).map { it * 2 }, first.turns.map { it.position })
        // 进度停在第 13 轮前面，没交给模型的 3 轮还在
        assertEquals(timeOf(25) - 1, first.consolidatedThrough)

        val second = pending(longChat, consolidatedAt = first.consolidatedThrough!!, updatedAt = timeOf(31))

        assertEquals(listOf(24, 26, 28), second.turns.map { it.position })
        assertEquals(3, second.totalTurns)
        assertEquals(timeOf(31), second.consolidatedThrough)
    }

    @Test
    fun `a conversation seen for the first time only looks back one batch`() {
        val start = initialMemoryProgress(longChat, zone)

        // 最近 12 轮从第 4 轮开始
        assertEquals(timeOf(7) - 1, start)
        assertEquals(12, pending(longChat, consolidatedAt = start).totalTurns)
        assertEquals(0L, initialMemoryProgress(emptyList(), zone))
    }

    @Test
    fun `turns sharing one timestamp still move progress forward`() {
        // 导入的对话可能每条消息都是同一个时间
        val imported = (1..14).flatMap { listOf(user(5, "question $it"), assistant(5)) }
        val start = initialMemoryProgress(imported, zone)

        val first = pending(imported, consolidatedAt = start)

        assertEquals(14, first.turns.size)
        assertEquals(imported, first.turns.flatMap { turn -> turn.messages.map { it.value } })
        assertEquals(timeOf(5), first.consolidatedThrough)
        assertTrue(pending(imported, consolidatedAt = first.consolidatedThrough!!).turns.isEmpty())
    }

    private fun pendingGroup(messages: List<UIMessage>, consolidatedAt: Long = 0, updatedAt: Long = 0) =
        pendingMemoryTurns(
            messages,
            updatedAt = updatedAt,
            consolidatedAt = consolidatedAt,
            timeZone = zone,
            groupChat = true,
        )

    @Test
    fun `群 assistant-only 发言各自成为待整理轮次`() {
        val messages = listOf(assistant(1), assistant(2), assistant(3))

        val result = pendingGroup(messages, updatedAt = timeOf(4))

        assertEquals(3, result.totalTurns)
        assertEquals(listOf(0, 1, 2), result.turns.map { it.position })
        assertEquals(messages.map { listOf(it) }, result.turns.map { turn -> turn.messages.map { it.value } })
        assertTrue(result.turns.all { it.isFinished })
        assertEquals(timeOf(4), result.consolidatedThrough)
    }

    @Test
    fun `单聊默认仍忽略没有 user 的 assistant-only 发言`() {
        val messages = listOf(assistant(1), assistant(2))

        val result = pending(messages, consolidatedAt = 0, updatedAt = timeOf(3))

        assertTrue(result.turns.isEmpty())
        assertEquals(0, result.totalTurns)
        assertEquals(0L, initialMemoryProgress(messages, zone))
    }

    @Test
    fun `已整理用户轮次之后的群独立接话不会被旧 user 时间吞掉`() {
        val messages = listOf(user(1), assistant(2), assistant(4), assistant(5))

        val result = pendingGroup(messages, consolidatedAt = timeOf(3), updatedAt = timeOf(6))

        assertEquals(2, result.totalTurns)
        assertEquals(listOf(2, 3), result.turns.map { it.position })
        assertEquals(listOf("reply 4", "reply 5"), result.turns.map { it.messages.single().value.toText() })
        assertEquals(timeOf(6), result.consolidatedThrough)
    }

    @Test
    fun `群 user 和首个 reply 同轮后续接话独立而单聊仍整轮处理`() {
        val messages = listOf(user(1), assistant(2), assistant(3), user(4), assistant(5), assistant(6))

        val group = pendingGroup(messages)
        val privateResult = pending(messages, consolidatedAt = 0)

        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4), listOf(5)), group.turns.map { turn ->
            turn.messages.map { it.index }
        })
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5)), privateResult.turns.map { turn ->
            turn.messages.map { it.index }
        })
        assertEquals(4, group.totalTurns)
        assertEquals(2, privateResult.totalTurns)
        assertTrue(pending(messages, consolidatedAt = timeOf(4)).turns.isEmpty())
    }

    @Test
    fun `群轮次跳过 synthetic checkpoint 和空发言但保留原始位置`() {
        val messages = listOf(
            assistant(1).copy(isSynthetic = true),
            assistant(2),
            user(3).copy(isContextCheckpoint = true),
            assistant(4, UIMessagePart.Text(" \n\t ")),
            assistant(5),
        )

        val result = pendingGroup(messages)

        assertEquals(listOf(1, 4), result.turns.map { it.position })
        assertEquals(listOf("reply 2", "reply 5"), result.turns.map { it.messages.single().value.toText() })
        assertEquals(2, result.totalTurns)
        assertEquals(timeOf(5), result.consolidatedThrough)
    }

    @Test
    fun `群 assistant-only 首次跟踪只回看最近十二次发言`() {
        val messages = (1..15).map { assistant(it) }

        val start = initialMemoryProgress(messages, zone, groupChat = true)
        val result = pendingGroup(messages, consolidatedAt = start)

        assertEquals(timeOf(4) - 1, start)
        assertEquals((3..14).toList(), result.turns.map { it.position })
        assertEquals(12, result.totalTurns)
        assertEquals(0L, initialMemoryProgress(emptyList(), zone, groupChat = true))
    }

    @Test
    fun `群独立接话积压按最旧十二轮分批且后续仍待整理`() {
        val messages = (1..15).map { assistant(it) }

        val first = pendingGroup(messages, updatedAt = timeOf(16))
        val second = pendingGroup(messages, consolidatedAt = first.consolidatedThrough!!, updatedAt = timeOf(16))

        assertEquals(15, first.totalTurns)
        assertEquals((0..11).toList(), first.turns.map { it.position })
        assertEquals(timeOf(13) - 1, first.consolidatedThrough)
        assertEquals(3, second.totalTurns)
        assertEquals(listOf(12, 13, 14), second.turns.map { it.position })
        assertEquals(timeOf(16), second.consolidatedThrough)
    }

    @Test
    fun `群最后一次接话含未完成工具时留待完成后整理`() {
        val waiting = assistant(3, UIMessagePart.Text("等待审批"), tool("workspace_shell", output = null))
        val messages = listOf(assistant(1), assistant(2), waiting)

        val first = pendingGroup(messages, updatedAt = timeOf(8))

        assertEquals(listOf(0, 1), first.turns.map { it.position })
        assertEquals(2, first.totalTurns)
        assertEquals(timeOf(3) - 1, first.consolidatedThrough)

        val completed = waiting.copy(parts = listOf(UIMessagePart.Text("执行完毕"), tool("workspace_shell")))
        val second = pendingGroup(messages.dropLast(1) + completed, consolidatedAt = first.consolidatedThrough!!)

        assertEquals(listOf(2), second.turns.map { it.position })
        assertEquals(listOf(completed), second.turns.single().messages.map { it.value })
        assertEquals(timeOf(3), second.consolidatedThrough)
    }

    @Test
    fun `群仅有带正文但工具未完成的发言时不推进进度`() {
        val waiting = assistant(3, UIMessagePart.Text("已有正文"), tool("workspace_shell", output = null))

        val result = pendingGroup(listOf(waiting), consolidatedAt = timeOf(3) - 1, updatedAt = timeOf(9))

        assertTrue(result.turns.isEmpty())
        assertEquals(0, result.totalTurns)
        assertNull(result.consolidatedThrough)
    }

    @Test
    fun `群末尾 user 尚无回复时保留该轮且不阻止前面独立接话`() {
        val result = pendingGroup(listOf(assistant(1), assistant(2), user(3)), updatedAt = timeOf(9))

        assertEquals(listOf(0, 1), result.turns.map { it.position })
        assertEquals(timeOf(3) - 1, result.consolidatedThrough)
    }

    @Test
    fun `群工具全部完成后才把最后一次独立接话判为结束`() {
        val completed = tool("search_web")
        val waiting = tool("workspace_shell", output = null)
        val last = assistant(2, UIMessagePart.Text("工具接话"), completed, waiting)
        val messages = listOf(assistant(1), last)

        val first = pendingGroup(messages)
        val finished = last.copy(parts = listOf(UIMessagePart.Text("工具接话"), completed, tool("workspace_shell")))
        val second = pendingGroup(listOf(messages.first(), finished), consolidatedAt = first.consolidatedThrough!!)

        assertEquals(listOf(0), first.turns.map { it.position })
        assertEquals(timeOf(2) - 1, first.consolidatedThrough)
        assertEquals(listOf(1), second.turns.map { it.position })
        assertTrue(second.turns.single().isFinished)
        assertEquals(timeOf(2), second.consolidatedThrough)
    }

    @Test
    fun `群 pending 时间边界严格排除已整理时间戳并包含其后发言`() {
        val messages = listOf(assistant(4), assistant(5), assistant(5), assistant(6))

        val result = pendingGroup(messages, consolidatedAt = timeOf(5))

        assertEquals(listOf(3), result.turns.map { it.position })
        assertEquals(1, result.totalTurns)
        assertEquals(timeOf(6), result.consolidatedThrough)
    }

    @Test
    fun `群同时间戳导入发言超过一批时仍推进而不重复整理`() {
        val messages = (1..14).map { assistant(5, UIMessagePart.Text("导入发言 $it")) }
        val start = initialMemoryProgress(messages, zone, groupChat = true)

        val first = pendingGroup(messages, consolidatedAt = start)
        val second = pendingGroup(messages, consolidatedAt = first.consolidatedThrough!!)

        assertEquals(timeOf(5) - 1, start)
        assertEquals(14, first.totalTurns)
        assertEquals((0..13).toList(), first.turns.map { it.position })
        assertEquals(messages, first.turns.flatMap { turn -> turn.messages.map { it.value } })
        // 同一时间戳必须完整展示一次，推进后既不漏掉尾部，也不重复首批。
        assertEquals(timeOf(5), first.consolidatedThrough)
        assertTrue(second.turns.isEmpty())
        assertEquals(0, second.totalTurns)
        assertNull(second.consolidatedThrough)
    }

    @Test
    fun `群批次切点跨入同时间戳时带上全部尾部但保留较晚发言待下批`() {
        val messages = (1..10).map { assistant(it) } +
            (1..4).map { assistant(11, UIMessagePart.Text("切点发言 $it")) } +
            listOf(assistant(12), assistant(13))

        val first = pendingGroup(messages, updatedAt = timeOf(14))
        val second = pendingGroup(messages, consolidatedAt = first.consolidatedThrough!!, updatedAt = timeOf(14))
        val third = pendingGroup(messages, consolidatedAt = second.consolidatedThrough!!, updatedAt = timeOf(14))

        assertEquals(16, first.totalTurns)
        assertEquals((0..13).toList(), first.turns.map { it.position })
        assertEquals(timeOf(12) - 1, first.consolidatedThrough)
        assertEquals(2, second.totalTurns)
        assertEquals(listOf(14, 15), second.turns.map { it.position })
        assertEquals(timeOf(14), second.consolidatedThrough)
        assertEquals(messages, (first.turns + second.turns).flatMap { turn -> turn.messages.map { it.value } })
        assertTrue(third.turns.isEmpty())
        assertEquals(0, third.totalTurns)
        assertNull(third.consolidatedThrough)
    }

    @Test
    fun `群同时间戳 user 和未完成工具在完成前不越过进度边界`() {
        val waiting = assistant(5, UIMessagePart.Text("等待工具"), tool("workspace_shell", output = null))
        val messages = listOf(user(5), waiting)
        val mark = timeOf(5) - 1

        val unfinished = pendingGroup(messages, consolidatedAt = mark, updatedAt = timeOf(9))

        assertTrue(unfinished.turns.isEmpty())
        assertNull(unfinished.consolidatedThrough)
        val finished = waiting.copy(parts = listOf(UIMessagePart.Text("工具完成"), tool("workspace_shell")))
        val completed = pendingGroup(listOf(messages.first(), finished), consolidatedAt = mark)

        assertEquals(listOf(0), completed.turns.map { it.position })
        assertEquals(listOf(0, 1), completed.turns.single().messages.map { it.index })
        assertEquals(timeOf(5), completed.consolidatedThrough)
        assertTrue(pendingGroup(listOf(messages.first(), finished), consolidatedAt = timeOf(5)).turns.isEmpty())
    }

    @Test
    fun `群同时间戳批次之后较晚的未完成工具保持可恢复边界`() {
        val completed = (1..13).map { assistant(5, UIMessagePart.Text("已完成 $it")) }
        val waiting = assistant(6, UIMessagePart.Text("等待工具"), tool("workspace_shell", output = null))

        val first = pendingGroup(completed + waiting, updatedAt = timeOf(9))

        assertEquals(13, first.totalTurns)
        assertEquals((0..12).toList(), first.turns.map { it.position })
        assertEquals(completed, first.turns.flatMap { turn -> turn.messages.map { it.value } })
        assertEquals(timeOf(6) - 1, first.consolidatedThrough)
        val finished = waiting.copy(parts = listOf(UIMessagePart.Text("完成工具"), tool("workspace_shell")))
        val second = pendingGroup(completed + finished, consolidatedAt = first.consolidatedThrough!!)

        assertEquals(listOf(13), second.turns.map { it.position })
        assertEquals(timeOf(6), second.consolidatedThrough)
    }

    @Test
    fun `candidates without new turns do not use up the scan limit`() = runBlocking {
        val checked = mutableListOf<Int>()
        // 前 8 个候选都没有新轮次，后面才是真正待整理的
        val found = collectPending(candidates = (1..20).toList(), limit = 2) { candidate ->
            checked += candidate
            if (candidate > 8) PendingMemoryConversation(Uuid.random(), "memory", turns = candidate) else null
        }

        assertEquals(listOf(9, 10), found.map { it.turns })
        // 凑够就停，不把剩下的候选都读一遍
        assertEquals((1..10).toList(), checked)
    }

    @Test
    fun `前五十个等待回复或审批的候选不阻塞后面的八个完成会话`() = runBlocking {
        val waitingForApproval = listOf(user(1), assistant(2, tool("workspace_shell", output = null)))
        listOf(listOf(user(1)), waitingForApproval).forEach { waiting ->
            val ids = (0 until 60).map(Int::toString)
            val pages = mutableListOf<Int>()
            val candidates = collectMemoryCandidateIds(50) { limit, offset ->
                pages += offset
                ids.drop(offset).take(limit)
            }
            val checked = mutableListOf<Int>()

            val found = collectPending(candidates, 8) { id ->
                val index = id.toInt()
                checked += index
                val messages = if (index < 50) waiting else listOf(user(1), assistant(2))
                val turns = pending(messages, consolidatedAt = 0)
                if (turns.turns.isEmpty()) null else PendingMemoryConversation(Uuid.random(), "memory", turns.totalTurns)
            }

            assertEquals(listOf(0, 50), pages)
            assertEquals(8, found.size)
            assertEquals((0 until 58).toList(), checked)
        }
    }

    @Test
    fun `前五十个未授权 owner 群不遮挡后面的个人记忆库发现和候选`() = runBlocking {
        val owner = Assistant(enableMemory = true)
        val member = Assistant(enableMemory = true)
        val deniedGroups = (0 until 50).map {
            Conversation(assistantId = owner.id, messageNodes = emptyList(), config = ConversationConfig(isGroupChat = true))
        }
        val ready = Conversation(assistantId = owner.id, messageNodes = emptyList())
        val conversations = deniedGroups + ready
        val settings = Settings(
            assistants = listOf(owner, member),
            groupChats = deniedGroups.map { GroupChat(conversationId = it.id, memberIds = listOf(member.id)) },
        )
        val candidates = collectMemoryCandidateIds(50) { limit, offset ->
            conversations.drop(offset).take(limit).map { it.id.toString() }
        }
        val targets = candidates.associateWith { id ->
            settings.memoryTargets(conversations.first { it.id.toString() == id }).filter { it.assistant.id == owner.id }
        }

        assertEquals(listOf(owner.id.toString()), candidates.flatMap { targets.getValue(it).map { it.memoryId } }.distinct())
        val found = collectPending(candidates, 8) { id ->
            targets.getValue(id).singleOrNull()?.let { PendingMemoryConversation(Uuid.parse(id), it.memoryId, 1) }
        }
        assertEquals(listOf(ready.id), found.map { it.conversationId })
        assertTrue(deniedGroups.all { targets.getValue(it.id.toString()).isEmpty() })
    }

    @Test
    fun `分页先固定 ID 避免轮次检查推进进度后 OFFSET 跳过候选`() = runBlocking {
        val remaining = (0 until 102).map(Int::toString).toMutableList()
        val pages = mutableListOf<Int>()
        val candidates = collectMemoryCandidateIds(50) { limit, offset ->
            pages += offset
            remaining.drop(offset).take(limit)
        }
        val found = collectPending(candidates, 2) { id ->
            if (id.toInt() < 100) {
                remaining.remove(id)
                null
            } else PendingMemoryConversation(Uuid.random(), "memory", id.toInt())
        }

        assertEquals(listOf(0, 50, 100), pages)
        assertEquals(listOf(100, 101), found.map { it.turns })
        assertEquals(listOf("100", "101"), remaining)
    }

    @Test
    fun `候选恰好整页和空页均可结束且保留顺序`() = runBlocking {
        listOf(0, 50, 100).forEach { size ->
            val ids = (0 until size).map(Int::toString)
            val pages = mutableListOf<Int>()

            val found = collectMemoryCandidateIds(50) { limit, offset ->
                pages += offset
                ids.drop(offset).take(limit)
            }

            assertEquals(ids, found)
            assertEquals((0..size step 50).toList(), pages)
        }
    }

    @Test
    fun `shared global library reviews once without merging assistant targets`() {
        val assistants = List(3) { Assistant(enableMemory = true, useGlobalMemory = true) }
        val passes = memoryConsolidationPasses(assistants, fullReview = true)
        assertEquals(assistants.map { it.id }, passes.map { it.first.id })
        assertEquals(listOf(false, false, true), passes.map { it.second })
        assertTrue(memoryConsolidationPasses(assistants, fullReview = false).none { it.second })
        assertTrue(memoryConsolidationPasses(emptyList(), fullReview = true).isEmpty())
        assertEquals(listOf(true), memoryConsolidationPasses(assistants.take(1), fullReview = true).map { it.second })
    }

    @Test
    fun `transcript numbers messages, cuts long ones and marks memory writes the user asked for`() {
        val written = tool(
            name = MemoryToolNames.STR_REPLACE,
            output = """{"path":"/topics/food.md","version":"abc"}""",
        )
        val rejected = tool(name = MemoryToolNames.APPEND, output = """{"error":"Version conflict"}""")
        val messages = listOf(
            user(1, "I no longer eat peanuts, remember that"),
            assistant(2, written, rejected, tool("search_web"), UIMessagePart.Text("Noted, and here is a long answer")),
        )

        val transcript = pending(messages, consolidatedAt = 0).turns.single().messages
            .toMemoryTranscript(userLength = 100, assistantLength = 10)

        assertEquals(
            """
                [USER #0]: I no longer eat peanuts, remember that

                [ASSISTANT #1]: Noted, and...

                [assistant updated memory: memory_str_replace /topics/food.md]
            """.trimIndent(),
            transcript,
        )
    }
}
