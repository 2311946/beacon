package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.uuid.Uuid

class GroupSpeakerSelectorTest {
    private val alpha = Assistant(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        name = "小甲",
    )
    private val beta = Assistant(
        id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
        name = "小乙",
    )
    private val gamma = Assistant(
        id = Uuid.parse("00000000-0000-0000-0000-000000000003"),
        name = "小丙",
    )
    private val members = listOf(gamma, alpha, beta)
    private val ids = listOf(gamma.id, alpha.id, beta.id)
    private val outsiderId = Uuid.parse("00000000-0000-0000-0000-000000000004")

    @Test
    fun `完整轮次的自动策略每位成员恰好参与一次`() {
        listOf(GroupActivationStrategy.LIST, GroupActivationStrategy.NATURAL, GroupActivationStrategy.POOLED)
            .forEach { strategy ->
                repeat(10) { seed ->
                    val picked = GroupSpeakerSelector.pickRound(
                        strategy, members + alpha, lastSpeakerId = beta.id,
                        naturalReply = alpha.name, random = Random(seed),
                    )
                    assertEquals(strategy.name, ids.size, picked.size)
                    assertEquals(strategy.name, ids.toSet(), picked.toSet())
                    if (strategy == GroupActivationStrategy.LIST) assertEquals(ids, picked)
                    if (strategy == GroupActivationStrategy.NATURAL) assertEquals(alpha.id, picked.first())
                    if (strategy == GroupActivationStrategy.POOLED) assertTrue(picked.first() != beta.id)
                }
            }
    }

    @Test
    fun `手动完整轮次只包含点名成员或跳过时的全部成员`() {
        assertEquals(listOf(beta.id), GroupSpeakerSelector.pickRound(
            GroupActivationStrategy.MANUAL, members, manualSpeakerId = beta.id,
        ))
        assertEquals(ids, GroupSpeakerSelector.pickRound(GroupActivationStrategy.MANUAL, members))
    }

    @Test
    fun `LIST 保留输入原顺序且不排除上一位发言人`() {
        assertEquals(ids, GroupSpeakerSelector.pickList(members))
        assertEquals(
            ids,
            GroupSpeakerSelector.pick(
                GroupActivationStrategy.LIST,
                members,
                lastSpeakerId = alpha.id,
                naturalReply = beta.name,
                manualSpeakerId = gamma.id,
            ),
        )
        assertEquals(ids, members.map { it.id })
    }

    @Test
    fun `所有策略在空名单时均返回空列表`() {
        GroupActivationStrategy.entries.forEach { strategy ->
            assertEquals(
                strategy.name,
                emptyList<Uuid>(),
                GroupSpeakerSelector.pick(
                    strategy,
                    emptyList(),
                    lastSpeakerId = alpha.id,
                    naturalReply = alpha.name,
                    manualSpeakerId = alpha.id,
                    random = Random(7),
                ),
            )
        }
    }

    @Test
    fun `NATURAL 唯一完整名称只选中对应成员`() {
        members.forEach { member ->
            assertEquals(member.id, GroupSpeakerSelector.matchName(member.name, members))
            assertEquals(
                listOf(member.id),
                GroupSpeakerSelector.pick(
                    GroupActivationStrategy.NATURAL,
                    members,
                    naturalReply = member.name,
                ),
            )
        }
    }

    @Test
    fun `NATURAL 接受外围空白及中英文双引号`() {
        listOf(" 小乙\n", "\"小乙\"", "“小乙”", " \" 小乙 \" ", " “ 小乙 ” ").forEach { reply ->
            assertEquals(reply, beta.id, GroupSpeakerSelector.matchName(reply, members))
            assertEquals(
                reply,
                listOf(beta.id),
                GroupSpeakerSelector.pick(GroupActivationStrategy.NATURAL, members, naturalReply = reply),
            )
        }
        assertEquals(
            beta.id,
            GroupSpeakerSelector.matchName("小乙", listOf(beta.copy(name = " 小乙 "))),
        )
    }

    @Test
    fun `NATURAL 空值未知名称部分名称及解释文本均按 LIST 原顺序回退`() {
        listOf(null, "", " \n\t", "\"\"", "“”", "不存在", "小", "小乙。", "我选择小乙", "小乙\n小甲")
            .forEach { reply ->
                assertNull(reply, GroupSpeakerSelector.matchName(reply, members))
                assertEquals(
                    reply,
                    ids,
                    GroupSpeakerSelector.pick(GroupActivationStrategy.NATURAL, members, naturalReply = reply),
                )
            }
    }

    @Test
    fun `NATURAL 重名不猜测而是按 LIST 原顺序回退`() {
        val duplicates = listOf(gamma, alpha.copy(name = beta.name), beta)
        assertNull(GroupSpeakerSelector.matchName(beta.name, duplicates))
        assertEquals(
            ids,
            GroupSpeakerSelector.pick(GroupActivationStrategy.NATURAL, duplicates, naturalReply = beta.name),
        )
    }

    @Test
    fun `NATURAL 去掉首尾空格后重名也必须回退`() {
        val duplicates = listOf(gamma, alpha.copy(name = " 小乙 "), beta)
        assertNull(GroupSpeakerSelector.matchName("“小乙”", duplicates))
        assertEquals(
            ids,
            GroupSpeakerSelector.pick(GroupActivationStrategy.NATURAL, duplicates, naturalReply = "“小乙”"),
        )
    }

    @Test
    fun `NATURAL 其他成员重名不妨碍唯一名称被选中`() {
        val duplicates = listOf(gamma, alpha.copy(name = beta.name), beta)
        assertEquals(
            listOf(gamma.id),
            GroupSpeakerSelector.pick(GroupActivationStrategy.NATURAL, duplicates, naturalReply = gamma.name),
        )
    }

    @Test
    fun `NATURAL 不选择已从可用名单中移除的成员`() {
        val remaining = listOf(gamma, alpha)
        assertEquals(
            listOf(gamma.id, alpha.id),
            GroupSpeakerSelector.pick(GroupActivationStrategy.NATURAL, remaining, naturalReply = beta.name),
        )
    }

    @Test
    fun `MANUAL 有效点名仅选择指定成员且不受上一人影响`() {
        members.forEach { member ->
            assertEquals(
                listOf(member.id),
                GroupSpeakerSelector.pick(
                    GroupActivationStrategy.MANUAL,
                    members,
                    lastSpeakerId = member.id,
                    manualSpeakerId = member.id,
                ),
            )
        }
    }

    @Test
    fun `MANUAL 跳过及无效点名按 LIST 原顺序回退`() {
        listOf(null, outsiderId).forEach { manualId ->
            assertEquals(
                ids,
                GroupSpeakerSelector.pick(GroupActivationStrategy.MANUAL, members, manualSpeakerId = manualId),
            )
        }
    }

    @Test
    fun `MANUAL 成员移除后点名失效并回退当前名单`() {
        assertEquals(
            listOf(gamma.id, alpha.id),
            GroupSpeakerSelector.pick(
                GroupActivationStrategy.MANUAL,
                listOf(gamma, alpha),
                manualSpeakerId = beta.id,
            ),
        )
    }

    @Test
    fun `POOLED 单成员即使是上一人也仍可发言`() {
        listOf(null, alpha.id, outsiderId).forEach { lastId ->
            assertEquals(
                listOf(alpha.id),
                GroupSpeakerSelector.pick(
                    GroupActivationStrategy.POOLED,
                    listOf(alpha),
                    lastSpeakerId = lastId,
                    random = Random(11),
                ),
            )
        }
    }

    @Test
    fun `POOLED 双成员时一定选择非上一人`() {
        listOf(alpha, beta).forEach { previous ->
            val expected = if (previous.id == alpha.id) beta.id else alpha.id
            repeat(32) { seed ->
                assertEquals(
                    listOf(expected),
                    GroupSpeakerSelector.pick(
                        GroupActivationStrategy.POOLED,
                        listOf(alpha, beta),
                        lastSpeakerId = previous.id,
                        random = Random(seed),
                    ),
                )
            }
        }
    }

    @Test
    fun `POOLED 多成员排除每一种上一人且覆盖其余候选`() {
        members.forEach { previous ->
            val seen = mutableSetOf<Uuid>()
            repeat(128) { seed ->
                val picked = GroupSpeakerSelector.pick(
                    GroupActivationStrategy.POOLED,
                    members,
                    lastSpeakerId = previous.id,
                    random = Random(seed),
                )
                assertEquals(1, picked.size)
                assertTrue(picked.single() in ids && picked.single() != previous.id)
                seen += picked.single()
            }
            assertEquals(ids.toSet() - previous.id, seen)
        }
    }

    @Test
    fun `POOLED 无上一人或上一人已移除时随机覆盖全部候选`() {
        listOf(null, outsiderId).forEach { lastId ->
            val seen = mutableSetOf<Uuid>()
            repeat(128) { seed ->
                val picked = GroupSpeakerSelector.pick(
                    GroupActivationStrategy.POOLED,
                    members,
                    lastSpeakerId = lastId,
                    random = Random(seed),
                )
                assertEquals(1, picked.size)
                assertTrue(picked.single() in ids)
                seen += picked.single()
            }
            assertEquals(ids.toSet(), seen)
        }
    }

    @Test
    fun `POOLED 使用注入随机源的索引而非固定挑选第一个成员`() {
        val candidates = listOf(gamma, beta)
        candidates.forEachIndexed { index, expected ->
            val random = object : Random() {
                override fun nextBits(bitCount: Int): Int = error("应通过有界索引选择候选")
                override fun nextInt(until: Int): Int {
                    assertEquals(candidates.size, until)
                    return index
                }
            }
            assertEquals(
                listOf(expected.id),
                GroupSpeakerSelector.pick(
                    GroupActivationStrategy.POOLED,
                    members,
                    lastSpeakerId = alpha.id,
                    random = random,
                ),
            )
        }
    }
}
