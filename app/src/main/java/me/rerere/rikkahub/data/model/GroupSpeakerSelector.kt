package me.rerere.rikkahub.data.model

import kotlin.random.Random
import kotlin.uuid.Uuid

/** 只负责选人；网络请求和手动选择的等待由群聊执行层负责。 */
object GroupSpeakerSelector {
    /** LIST 保持原行为：按名单顺序选中全部未禁言成员。 */
    fun pickList(members: List<Assistant>): List<Uuid> = members.map { it.id }

    /** 一轮是完整的参与名单，不是一次单人抽取。自然/随机策略只改变本轮顺序。 */
    fun pickRound(
        strategy: GroupActivationStrategy,
        enabledMembers: List<Assistant>,
        lastSpeakerId: Uuid? = null,
        naturalReply: String? = null,
        manualSpeakerId: Uuid? = null,
        random: Random = Random.Default,
    ): List<Uuid> {
        val members = enabledMembers.distinctBy { it.id }
        val first = pick(strategy, members, lastSpeakerId, naturalReply, manualSpeakerId, random)
        if (strategy == GroupActivationStrategy.MANUAL || strategy == GroupActivationStrategy.LIST) return first
        val remaining = members.map { it.id }.filterNot { it in first }
        return first + if (strategy == GroupActivationStrategy.POOLED) remaining.shuffled(random) else remaining
    }

    /** 只接受唯一的完整角色名，重名或解释性输出不猜测，交给 LIST 回退。 */
    fun matchName(reply: String?, members: List<Assistant>): Uuid? {
        val name = reply?.trim()?.removeSurrounding("\"")?.removeSurrounding("“", "”")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        return members.filter { it.name.trim() == name }.singleOrNull()?.id
    }

    fun pick(
        strategy: GroupActivationStrategy,
        enabledMembers: List<Assistant>,
        lastSpeakerId: Uuid? = null,
        naturalReply: String? = null,
        manualSpeakerId: Uuid? = null,
        random: Random = Random.Default,
    ): List<Uuid> = when (strategy) {
        GroupActivationStrategy.LIST -> pickList(enabledMembers)
        GroupActivationStrategy.NATURAL -> matchName(naturalReply, enabledMembers)
            ?.let { listOf(it) } ?: pickList(enabledMembers)
        GroupActivationStrategy.MANUAL -> manualSpeakerId
            ?.takeIf { id -> enabledMembers.any { it.id == id } }
            ?.let { listOf(it) } ?: pickList(enabledMembers)
        GroupActivationStrategy.POOLED -> {
            val candidates = enabledMembers.filterNot { it.id == lastSpeakerId }
                .ifEmpty { enabledMembers }
            candidates.randomOrNull(random)?.let { listOf(it.id) }.orEmpty()
        }
    }
}
