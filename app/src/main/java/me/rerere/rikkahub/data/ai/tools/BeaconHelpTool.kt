package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val MAX_HELP_QUERY_LENGTH = 200
private const val MAX_HELP_RESULTS = 3
private const val MAX_HELP_CONTENT_LENGTH = 600
private const val INVALID_HELP_ARGUMENTS = "参数无效：仅接受 query 字符串，须非空且不超过 200 字符，不允许额外字段。"
private const val HELP_SYSTEM_PROMPT =
    "回答 Beacon 功能或设置问题前，先调用 search_beacon_help 检索，以返回的内置说明为准；未找到或说明未覆盖时明确说不知道，不编造路径、默认值或功能。"

private const val AI_SETTINGS_HELP =
    "入口：助手详情 → 本地工具 → 允许AI修改设置。默认关闭（false）。开启仅允许此助手申请修改白名单配置，每次修改仍需审批，不代表自动执行或全部设置均可修改。关闭后隐藏配置修改工具，并阻止尚未执行的调用；唤醒全局参数会影响其他角色。本帮助工具只检索内置说明，不读取或修改实际设置。"
private const val MEMORY_EMBEDDING_HELP =
    "入口：助手详情 → 记忆 → 设置 → 语义记忆检索（全局）。启用语义记忆检索默认关闭，填写 Embedding Endpoint、API Key 和 Embedding Model 后需点击保存语义检索设置才生效。它是全局配置，作用于所有助手的个人库和全局库；启用并保存后会将所有 ACTIVE 记忆正文片段及当前查询发送到指定服务商，可能产生费用。不要在帮助查询中粘贴记忆正文或密钥。"
private const val GITHUB_BACKUP_HELP =
    "入口：设置 → 数据备份 → GitHub。点击配置 GitHub 备份，在访问令牌（Token）处填写令牌并测试并连接。GitHub Token 仅保存在本机，不进入 BeaconZIP 或系统备份，但 ZIP 未加密且始终包含设置，其中可能有 API 密钥等其他凭据；不要公开上传或随意分享。每次备份创建新版本，不覆盖旧版本。恢复会覆盖所选本机数据，设置始终恢复；先备份当前数据，再选择历史版本并确认，恢复成功后需重启应用。不要把 Token 粘贴给模型。"
private const val PARAGRAPH_BUBBLES_HELP =
    "入口：设置 → 偏好设置 → 界面偏好设置 → 消息显示 → 助手按段落拆分气泡。助手气泡或主题气泡生效时，普通正文按空行拆分并保留消息美化；含 Markdown 结构、折叠或生图描述时整段保留。单聊与群聊共用，不影响通话和导出。不要把所有消息都会拆分当作保证。"
private const val THEME_HELP =
    "已核对入口：设置 → 通用设置分组 → 主题。主题入口与偏好设置入口是同级项，不应编造成必须先进入界面偏好设置才能打开主题。此内置说明不覆盖所有主题选项或版本差异，未覆盖的选项及路径应明确说不知道。"
private const val GROUP_SUMMARY_HELP =
    "群聊设置或群聊全局默认设置中有自动总结分组，包含启用自动总结、自动总结消息阈值和保留最近消息条数。内置默认启用，阈值为 80 条，保留最近 20 条原始消息；实际值可能已自定义或继承全局设置。总结只压缩发送给模型的请求：较早内容使用摘要，聊天历史原文仍完整保留，不是删除记录。自动总结会产生额外模型调用费用，失败不阻断聊天；修改后点击保存生效。"

private data class BeaconHelpArticle(
    val id: String,
    val title: String,
    val content: String,
    val keywords: List<String>,
)

private val BEACON_HELP_ARTICLES = listOf(
    BeaconHelpArticle(
        id = "ai_settings_approval",
        title = "允许AI修改设置与审批",
        content = AI_SETTINGS_HELP,
        keywords = listOf("修改设置", "改设置", "设置修改", "允许ai", "ai修改", "审批", "白名单", "本地工具", "配置修改"),
    ),
    BeaconHelpArticle(
        id = "memory_embedding",
        title = "语义记忆检索与 Embedding",
        content = MEMORY_EMBEDDING_HELP,
        keywords = listOf("记忆", "embedding", "语义", "向量", "正文发送", "召回"),
    ),
    BeaconHelpArticle(
        id = "github_backup",
        title = "GitHub 备份、凭据与恢复",
        content = GITHUB_BACKUP_HELP,
        keywords = listOf("github", "备份", "恢复", "token", "zip", "凭据", "令牌"),
    ),
    BeaconHelpArticle(
        id = "paragraph_bubbles",
        title = "助手按段落拆分气泡",
        content = PARAGRAPH_BUBBLES_HELP,
        keywords = listOf("段落", "气泡", "拆分", "空行", "消息显示"),
    ),
    BeaconHelpArticle(
        id = "theme",
        title = "主题入口",
        content = THEME_HELP,
        keywords = listOf("主题", "theme", "配色"),
    ),
    BeaconHelpArticle(
        id = "group_summary",
        title = "群聊自动总结与原文保留",
        content = GROUP_SUMMARY_HELP,
        keywords = listOf("群聊", "群总结", "总结", "摘要", "80/20", "保留原文", "聊天历史", "压缩"),
    ),
)

fun buildBeaconHelpTool(): Tool = Tool(
    name = "search_beacon_help",
    description = "按主题关键词检索本地内置 Beacon 功能和设置说明，最多返回 3 条；不读取用户数据、不联网、不修改设置。",
    systemPrompt = { _, _ -> HELP_SYSTEM_PROMPT },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "帮助主题关键词或问题；非空，最多 200 字符，不要包含正文或凭据。不允许额外字段。")
                    put("minLength", 1)
                    put("maxLength", MAX_HELP_QUERY_LENGTH)
                })
            },
            required = listOf("query"),
        )
    },
    needsApproval = { false },
    execute = { arguments ->
        val query = validatedHelpQuery(arguments)
        val result = if (query == null) {
            buildJsonObject {
                put("status", "invalid_arguments")
                put("error", INVALID_HELP_ARGUMENTS)
            }
        } else {
            val articles = BEACON_HELP_ARTICLES
                .map { article -> article to article.keywords.count { query.contains(it, ignoreCase = true) } }
                .filter { (_, score) -> score > 0 }
                .sortedByDescending { (_, score) -> score }
                .take(MAX_HELP_RESULTS)
            buildJsonObject {
                put("status", if (articles.isEmpty()) "not_found" else "ok")
                if (articles.isEmpty()) {
                    put("message", "未找到相关内置说明；不知道该问题的答案，请勿编造功能或路径。")
                }
                put("articles", buildJsonArray {
                    articles.forEach { (article, _) ->
                        add(buildJsonObject {
                            put("id", article.id)
                            put("title", article.title)
                            put("content", article.content.take(MAX_HELP_CONTENT_LENGTH))
                        })
                    }
                })
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

private fun validatedHelpQuery(arguments: JsonElement): String? {
    val objectArguments = arguments as? JsonObject ?: return null
    if (objectArguments.keys != setOf("query")) return null
    val query = objectArguments["query"] as? JsonPrimitive ?: return null
    if (!query.isString) return null
    return query.content.takeIf { it.length <= MAX_HELP_QUERY_LENGTH && it.isNotBlank() }?.trim()
}
