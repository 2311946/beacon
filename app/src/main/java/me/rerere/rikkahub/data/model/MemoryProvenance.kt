package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** 存储命名空间不是群ID，也不是“在场成员都知道”的证明。全局库仅沿用用户现有的显式共享选择。 */
@Serializable
data class MemoryScope(val namespace: String, val kind: MemoryScopeKind) {
    init {
        require(namespace.isNotBlank())
        require((namespace == GLOBAL_NAMESPACE) == (kind == MemoryScopeKind.EXPLICIT_GLOBAL))
    }

    fun allowsNamespace(requestedNamespace: String): Boolean = namespace == requestedNamespace

    companion object {
        const val GLOBAL_NAMESPACE = "__global__"
        fun of(namespace: String) = MemoryScope(
            namespace,
            if (namespace == GLOBAL_NAMESPACE) MemoryScopeKind.EXPLICIT_GLOBAL else MemoryScopeKind.ASSISTANT,
        )
    }
}

@Serializable
enum class MemoryScopeKind { ASSISTANT, EXPLICIT_GLOBAL }

@Serializable
enum class MemoryCategory { UNKNOWN, USER_FACT, ROLE_EXPERIENCE, EXTERNAL }

@Serializable
enum class MemoryLifecycle { ACTIVE, ARCHIVED, QUARANTINED, SUPERSEDED }

@Serializable
enum class MemorySourceKind { UNKNOWN, MANUAL, PRIVATE_CHAT, GROUP_CHAT, EXTERNAL_IMPORT, COPY }

/** 文本变化后旧证据可保留审计，但不能继续宣称它证明了当前全文。 */
@Serializable
enum class MemorySourceStatus { CURRENT, STALE }

/** 可信调用者提供的来源，不暴露为模型可任意指定的 memory_* 参数。仅保存引用，不复制聊天正文。 */
@Serializable
data class MemorySource(
    val kind: MemorySourceKind,
    val conversationId: String? = null,
    val messageId: String? = null,
    val groupId: String? = null,
    val speakerId: String? = null,
    val participantIds: List<String> = emptyList(),
    val sourceContentHash: String? = null,
    val occurredAt: Long? = null,
    val externalSystem: String? = null,
    val externalId: String? = null,
    val externalVersion: String? = null,
    val copiedFromNamespace: String? = null,
    val copiedFromPath: String? = null,
) {
    fun validate() {
        require(messageId == null || !conversationId.isNullOrBlank()) { "A message source requires its conversation ID" }
        require(kind != MemorySourceKind.PRIVATE_CHAT || !conversationId.isNullOrBlank())
        require(kind != MemorySourceKind.GROUP_CHAT || (!conversationId.isNullOrBlank() && !groupId.isNullOrBlank()))
        require(kind != MemorySourceKind.EXTERNAL_IMPORT || (!externalSystem.isNullOrBlank() && !externalId.isNullOrBlank()))
        require(kind != MemorySourceKind.COPY || (!copiedFromNamespace.isNullOrBlank() && !copiedFromPath.isNullOrBlank()))
        require(participantIds.none { it.isBlank() })
    }
}

/** null context 意味着调用者没有提供可验证来源，绝不从记忆正文或当前角色猜来源。 */
data class MemoryWriteContext(
    val category: MemoryCategory? = null,
    val sources: List<MemorySource> = emptyList(),
) {
    fun validate() = sources.forEach { it.validate() }
}

/** 独立于界面乐观锁的12位version，索引绑定完整SHA256，避免把并发版本误当历史修订。 */
fun memoryContentHash(content: String): String = MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
