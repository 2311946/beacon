package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.MemoryWriteContext
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import java.time.Instant

object MemoryToolNames {
    const val READ = "memory_read"
    const val WRITE = "memory_write"
    const val STR_REPLACE = "memory_str_replace"
    const val APPEND = "memory_append"
    const val DELETE = "memory_delete"
    const val LIST = "memory_list"

    /** 会改动记忆库的工具 */
    val WRITES = setOf(WRITE, STR_REPLACE, APPEND, DELETE)
}

private const val MAX_READ_PATHS = 20

private const val IF_VERSION_DESCRIPTION =
    "The version returned by the most recent read or write of this file"

/**
 * 记忆库的读写工具，[assistantId] 决定操作哪个记忆库（助手自己的或全局的）。
 *
 * 何时读、何时写由 system prompt 里的记忆片段约束，这里只描述每个工具本身。
 */
fun buildMemoryTools(
    repository: MemoryRepository,
    assistantId: String,
    // 整理器只接受已展示且重新校验过的引用；模型不能指定 namespace 或伪造来源元数据。
    writeContext: (suspend (List<String>) -> MemoryWriteContext)? = null,
    beforeAccess: suspend () -> Unit = {},
    validateWrite: (suspend (path: String, candidateFullContent: String, refs: List<String>) -> Unit)? = null,
): List<Tool> = listOf(
    Tool(
        name = MemoryToolNames.READ,
        description = "Read one or more memory files. Returns each file's content and version.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("maxItems", MAX_READ_PATHS)
                        put("description", "Paths of the files to read, e.g. [\"/topics/food.md\"]")
                    })
                },
                required = listOf("path")
            )
        },
        execute = { args ->
            beforeAccess()
            // 有些模型只读一个文件时会直接传字符串
            val paths = when (val path = args.jsonObject["path"]) {
                is JsonArray -> path.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(path.contentOrNull)
                else -> emptyList()
            }.take(MAX_READ_PATHS)
            if (paths.isEmpty()) error("path is required")

            val files = paths.associateWith { repository.getActiveFile(assistantId, it) }
            toolOutput {
                put("files", buildJsonArray {
                    files.values.filterNotNull().forEach { file ->
                        add(buildJsonObject {
                            put("path", file.path)
                            put("version", file.version)
                            put("content", file.content)
                        })
                    }
                })
                val notFound = files.filterValues { it == null }.keys
                if (notFound.isNotEmpty()) {
                    put("not_found", buildJsonArray { notFound.forEach { add(it) } })
                }
            }
        }
    ),
    Tool(
        name = MemoryToolNames.WRITE,
        description = """
            Create a memory file or overwrite it entirely. content is the complete file; lines not included are removed.
            if_version: pass "new" for a new file, or the version returned by the most recent read or write of an existing file.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringSchema("Path of the file, e.g. /topics/food.md"))
                    put("content", stringSchema("The complete file content"))
                    if (writeContext != null) put("source_refs", memorySourceRefsSchema())
                    put("if_version", stringSchema("\"new\" for a new file, otherwise the file's current version"))
                },
                required = listOf("path", "content", "if_version") + if (writeContext != null) listOf("source_refs") else emptyList()
            )
        },
        execute = { args ->
            beforeAccess()
            val path = args.string("path")
            val content = args.string("content")
            guarded(content) {
                val refs = args.sourceRefs()
                val context = writeContext?.invoke(refs)
                MemoryFile.normalizePath(path)?.let { validateWrite?.invoke(it, content, refs) }
                repository.writeFile(assistantId, path, content, args.string("if_version"), context = context, activeOnly = true)
            }.toToolOutput(path)
        }
    ),
    Tool(
        name = MemoryToolNames.STR_REPLACE,
        description = """
            Replace the single occurrence of old_str in a memory file with new_str. An empty new_str deletes it.
            Rejected when old_str matches zero or several places.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringSchema("Path of the file"))
                    put("old_str", stringSchema("Text to replace; must match exactly one place"))
                    put("new_str", stringSchema("Replacement text; empty to delete"))
                    if (writeContext != null) put("source_refs", memorySourceRefsSchema())
                    put("if_version", stringSchema(IF_VERSION_DESCRIPTION))
                },
                required = listOf("path", "old_str", "new_str", "if_version") + if (writeContext != null) listOf("source_refs") else emptyList()
            )
        },
        execute = { args ->
            beforeAccess()
            val path = args.string("path")
            val oldStr = args.string("old_str")
            val newStr = args.string("new_str")
            guarded(newStr) {
                val refs = args.sourceRefs()
                val context = writeContext?.invoke(refs)
                if (validateWrite != null) {
                    repository.getActiveFile(assistantId, path)?.let { current ->
                        replacementCandidate(current.content, oldStr, newStr)?.let { candidate ->
                            validateWrite(current.path, candidate, refs)
                        }
                    }
                }
                repository.replaceInFile(assistantId, path, oldStr, newStr, args.string("if_version"), context = context, activeOnly = true)
            }.toToolOutput(path)
        }
    ),
    Tool(
        name = MemoryToolNames.APPEND,
        description = "Append one line to the end of a memory file.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringSchema("Path of the file"))
                    put("content", stringSchema("The line to append"))
                    if (writeContext != null) put("source_refs", memorySourceRefsSchema())
                    put("if_version", stringSchema(IF_VERSION_DESCRIPTION))
                },
                required = listOf("path", "content", "if_version") + if (writeContext != null) listOf("source_refs") else emptyList()
            )
        },
        execute = { args ->
            beforeAccess()
            val path = args.string("path")
            val content = args.string("content")
            if (content.isBlank()) error("content must not be blank")
            guarded(content) {
                val refs = args.sourceRefs()
                val context = writeContext?.invoke(refs)
                if (validateWrite != null) {
                    repository.getActiveFile(assistantId, path)?.let { current ->
                        val separator = if (current.content.isEmpty() || current.content.endsWith("\n")) "" else "\n"
                        validateWrite(current.path, current.content + separator + content.trimEnd('\n', '\r') + "\n", refs)
                    }
                }
                repository.appendToFile(assistantId, path, content, args.string("if_version"), context = context, activeOnly = true)
            }.toToolOutput(path)
        }
    ),
    Tool(
        name = MemoryToolNames.DELETE,
        description = "Delete an entire memory file. Only use when the user explicitly asks to forget a whole topic.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringSchema("Path of the file"))
                    put("if_version", stringSchema(IF_VERSION_DESCRIPTION))
                },
                required = listOf("path", "if_version")
            )
        },
        execute = { args ->
            beforeAccess()
            val path = args.string("path")
            repository.deleteFile(assistantId, path, args.string("if_version"), activeOnly = true).toToolOutput(path)
        }
    ),
    Tool(
        name = MemoryToolNames.LIST,
        description = "List memory files (path, size, update time), optionally filtered by path prefix.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path_prefix", stringSchema("Only list files whose path starts with this, e.g. /people/"))
                }
            )
        },
        execute = { args ->
            beforeAccess()
            val prefix = (args.jsonObject["path_prefix"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val files = repository.getActiveFiles(assistantId).filter { it.path.startsWith(prefix) }
            toolOutput {
                put("files", buildJsonArray {
                    files.forEach { file ->
                        add(buildJsonObject {
                            put("path", file.path)
                            put("size", file.content.toByteArray().size)
                            put("updated_at", Instant.ofEpochMilli(file.updatedAt).toString())
                        })
                    }
                })
            }
        }
    ),
)

// 必须与 Repository 的唯一匹配和整行删除规则一致；不匹配时由 Repository 返回原有错误。
private fun replacementCandidate(content: String, oldStr: String, newStr: String): String? {
    if (oldStr.isEmpty()) return null
    val index = content.indexOf(oldStr)
    if (index < 0 || content.indexOf(oldStr, index + 1) >= 0) return null
    var start = index
    var end = index + oldStr.length
    if (newStr.isEmpty()) {
        val atLineStart = start == 0 || content[start - 1] == '\n'
        val atLineEnd = end == content.length || content[end] == '\n'
        if (atLineStart && atLineEnd) {
            if (end < content.length) end++ else if (start > 0) start--
        }
    }
    return content.substring(0, start) + newStr + content.substring(end)
}

/** 这次工具调用成功改动的记忆文件路径；不是写工具、还没执行或写入被拒绝时返回 null */
fun UIMessagePart.Tool.memoryWritePath(): String? {
    if (toolName !in MemoryToolNames.WRITES || !isExecuted) return null
    val result = runCatching {
        Json.parseToJsonElement(output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
    }.getOrNull() as? JsonObject ?: return null
    if ("error" in result) return null
    return (result["path"] as? JsonPrimitive)?.contentOrNull
}

private fun memorySourceRefsSchema() = buildJsonObject {
    put("type", "array")
    put("items", stringSchema("Exact conversationId/messageId reference already shown in the transcript or conversation tools"))
    put("maxItems", 40)
    put("description", "References for this write only. Never claim another speaker's statements as your own experience. Empty only for full-review rearrangement without new facts.")
}

private fun JsonElement.sourceRefs(): List<String> {
    val refs = jsonObject["source_refs"] ?: return emptyList()
    require(refs is JsonArray && refs.size <= 40) { "source_refs must be an array of at most 40 references" }
    return refs.map {
        val value = it as? JsonPrimitive ?: error("Invalid source reference")
        require(value.isString && value.content.isNotBlank() && value.content.length <= 100) { "Invalid source reference" }
        value.content
    }.distinct()
}

private fun stringSchema(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun JsonElement.string(key: String): String =
    (jsonObject[key] as? JsonPrimitive)?.contentOrNull ?: error("$key is required")

private fun toolOutput(builder: JsonObjectBuilder.() -> Unit): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject(builder).toString()))

private inline fun guarded(content: String, write: () -> MemoryWriteResult): MemoryWriteResult {
    val forbidden = findForbiddenMemoryContent(content) ?: return write()
    return MemoryWriteResult.Rejected(
        "Refused: the content looks like it contains $forbidden, which is never stored in memory. " +
            "Leave it out, and tell the user in one sentence that it cannot be saved."
    )
}

// 冲突时带上最新内容和版本，模型可以在同一轮里合并后重试
private fun MemoryWriteResult.toToolOutput(path: String): List<UIMessagePart> = toolOutput {
    when (val result = this@toToolOutput) {
        is MemoryWriteResult.Success -> {
            val file = result.file
            put("path", file?.path ?: path)
            if (file != null) put("version", file.version) else put("deleted", true)
        }

        is MemoryWriteResult.Conflict -> putConflict(path, result.current)
        is MemoryWriteResult.Rejected -> put("error", result.reason)
    }
}

private fun JsonObjectBuilder.putConflict(path: String, current: MemoryFile?) {
    if (current == null) {
        put(
            "error",
            "$path does not exist. Create it with ${MemoryToolNames.WRITE} and " +
                "if_version=\"${MemoryRepository.NEW_FILE_VERSION}\"."
        )
        return
    }
    put(
        "error",
        "Version conflict: $path already exists or has changed since you read it. " +
            "Merge your change into current_content and retry with current_version."
    )
    put("current_version", current.version)
    put("current_content", current.content)
}
