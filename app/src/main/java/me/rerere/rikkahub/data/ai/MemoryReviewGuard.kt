package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.MemoryFile

/** 只证明整理保留了快照完整行，不认证快照中的事实或来源。 */
class MemoryReviewGuard(trustedSnapshot: List<MemoryFile>) {
    private val trustedLines = trustedSnapshot.flatMap { file ->
        file.content.lineSequence().filter(String::isNotBlank).toList()
    }.toSet()

    fun validateWrite(path: String, candidateFullContent: String) {
        requireNotNull(MemoryFile.normalizePath(path)) { "Invalid memory path: $path" }
        check(candidateFullContent.lineSequence().filter(String::isNotBlank).all { it in trustedLines }) {
            "source_refs is required for new memory facts; full-review tidy may only move, reorder or remove exact existing lines, including frontmatter."
        }
    }
}
