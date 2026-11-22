package me.rerere.rikkahub.data.sync.github

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GitHubReleaseClient private constructor(
    private val client: OkHttpClient,
    private val endpoints: GitHubEndpoints,
    private val now: () -> LocalDateTime,
) {
    constructor() : this(dedicatedClient(), GitHubEndpoints(), LocalDateTime::now)

    suspend fun connect(token: String): GitHubBackupConfig = safeGitHubOperation {
        val credentials = GitHubBackupConfig("beacon", "beacon-backup", token.trim())
        credentials.validate()
        val user = request(credentials, endpoints.api.newBuilder().addPathSegment("user").build()).read { response ->
            checkConnectStatus(response, 200)
            parseJson(response) as? JsonObject ?: invalidResponse()
        }
        val login = user.text("login")
        if (!Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?").matches(login) || login.contains("--")) {
            throw GitHubBackupException("GitHub 登录名无效，已停止连接，未创建仓库")
        }
        val config = GitHubBackupConfig(login, "beacon-backup", credentials.token)
        config.validate()
        val existing = connectionRepository(config, allowMissing = true)
        if (existing != null) {
            validateConnectionRepository(existing, config)
        } else {
            currentCoroutineContext().ensureActive()
            val body = buildJsonObject {
                put("name", config.repository)
                put("private", true)
                put("auto_init", true)
                put("description", "Beacon 私有备份仓库")
            }.toString().toRequestBody(JSON_TYPE)
            val created = request(config, endpoints.api.newBuilder().addPathSegments("user/repos").build(), body).read { response ->
                if (response.code == 422) null else {
                    checkConnectStatus(response, 201, creating = true)
                    parseJson(response) as? JsonObject ?: invalidResponse()
                }
            }
            if (created != null) validateConnectionRepository(created, config)
            val confirmed = connectionRepository(config, afterConflict = created == null) ?: invalidResponse()
            validateConnectionRepository(confirmed, config)
        }
        // 连接仅确认私库可访问；缺少 permissions.push 不代表已验证 Contents 写权限。
        config
    }

    private suspend fun connectionRepository(
        config: GitHubBackupConfig,
        allowMissing: Boolean = false,
        afterConflict: Boolean = false,
    ): JsonObject? {
        currentCoroutineContext().ensureActive()
        return request(config, apiUrl(config, "")).read { response ->
            if (allowMissing && response.code == 404) null else {
                checkConnectStatus(response, 200, afterConflict = afterConflict)
                parseJson(response) as? JsonObject ?: invalidResponse()
            }
        }
    }

    private fun validateConnectionRepository(repo: JsonObject, config: GitHubBackupConfig) {
        if (repo.optionalBoolean("private") != true) {
            throw GitHubBackupException("仅允许连接明确为 private 的私有 GitHub 仓库；不会修改公开仓库或删除已有仓库")
        }
        if (repo.containsKey("name") && !repo.text("name").equals(config.repository, ignoreCase = true)) {
            throw GitHubBackupException("GitHub 返回的仓库名称与目标不符，已停止连接")
        }
        if (repo.containsKey("owner")) {
            val owner = repo["owner"] as? JsonObject ?: invalidResponse()
            if (owner.containsKey("login") && !owner.text("login").equals(config.owner, ignoreCase = true)) {
                throw GitHubBackupException("GitHub 返回的仓库所有者与登录账号不符，已停止连接")
            }
        }
        if (repo.optionalBoolean("archived") == true || repo.optionalBoolean("disabled") == true) {
            throw GitHubBackupException("目标 GitHub 仓库已归档或禁用，无法用于备份写入")
        }
        if (repo.containsKey("permissions")) {
            val permissions = repo["permissions"] as? JsonObject ?: invalidResponse()
            if (permissions.optionalBoolean("push") == false) {
                throw GitHubBackupException("当前 Token 明确没有仓库写权限，请检查 Repository access 是否包含 beacon-backup，以及 Contents: Read and write；连接未完成")
            }
        }
    }

    suspend fun test(config: GitHubBackupConfig): Unit = safeGitHubOperation {
        checkPrivate(config)
    }

    suspend fun list(config: GitHubBackupConfig): List<GitHubBackupItem> = safeGitHubOperation {
        checkPrivate(config)
        val result = mutableListOf<GitHubBackupItem>()
        releases(config).forEach { release ->
            val tag = release.text("tag_name")
            if (isBeaconTag(tag) && !release.isDraft()) {
                val releaseId = release.positiveId()
                pages(config, "releases/$releaseId/assets").forEach { asset ->
                    if (isBeaconZip(asset.text("name"))) {
                        result += asset.toItem(tag, release.optionalText("name") ?: tag)
                    }
                }
            }
        }
        result.distinctBy { it.assetId }.sortedByDescending { it.createdAt }
    }

    suspend fun upload(config: GitHubBackupConfig, archive: File): GitHubBackupItem = safeGitHubOperation {
        checkPrivate(config)
        if (!archive.isFile || archive.length() !in 1..MAX_ARCHIVE_BYTES) {
            throw GitHubBackupException("备份 ZIP 为空或超过 2 GiB，无法上传")
        }
        val time = now()
        val dateTag = "beacon-backup-${time.toLocalDate()}"
        val existingTags = releases(config).map { it.text("tag_name") }.toSet()
        var tag = if (existingTags.any { it == dateTag || it.startsWith("$dateTag-") }) uniqueTag(time) else dateTag
        var release: JsonObject? = null
        // 并发备份可能抢占当天 tag；只新建独立 Release，不覆盖或删除任何历史。
        repeat(2) { attempt ->
            if (release == null) {
                val payload = buildJsonObject {
                    put("tag_name", tag)
                    put("name", tag)
                    put("draft", true)
                    put("prerelease", false)
                }.toString().toRequestBody(JSON_TYPE)
                val response = json(config, "releases", payload, allowConflict = true)
                if (response == null) {
                    if (attempt == 1) throw GitHubBackupException("创建备份版本冲突，请重试；已有备份未改变")
                    tag = uniqueTag(time)
                } else {
                    release = response as? JsonObject ?: invalidResponse()
                }
            }
        }
        val releaseId = requireNotNull(release).positiveId()
        val url = endpoints.upload.newBuilder()
            .addPathSegments("repos/${config.owner}/${config.repository}/releases/$releaseId/assets")
            .addQueryParameter("name", "$tag.zip")
            .build()
        val asset = request(config, url, archive.asRequestBody(ZIP_TYPE)).read { response ->
            checkStatus(response, 201)
            parseJson(response) as? JsonObject ?: invalidResponse()
        }
        if (asset.text("name") != "$tag.zip" || asset.number("size") != archive.length()) invalidResponse()
        val published = buildJsonObject {
            put("draft", false)
            put("make_latest", "false")
        }.toString().toRequestBody(JSON_TYPE)
        json(config, "releases/$releaseId", published, method = "PATCH")
        asset.toItem(tag, tag)
    }

    // 文件的所有权由调用者持有；失败或取消时在调用者 finally 中删除。
    internal suspend fun download(config: GitHubBackupConfig, item: GitHubBackupItem, target: File) {
        safeGitHubOperation {
            checkPrivate(config)
            if (item.assetId <= 0 || !isBeaconTag(item.tag) || !isBeaconZip(item.name)) invalidResponse()
            val metadata = json(config, "releases/assets/${item.assetId}") as? JsonObject ?: invalidResponse()
            val actual = metadata.toItem(item.tag, item.releaseName)
            if (actual.assetId != item.assetId || actual.name != item.name || actual.size != item.size ||
                (item.digest != null && actual.digest != item.digest)
            ) {
                throw GitHubBackupException("备份附件信息已变化，请刷新备份列表后重试")
            }
            validateDownloadSize(actual.size)
            val expectedDigest = actual.digest?.let { digest ->
                if (!Regex("sha256:[0-9a-fA-F]{64}").matches(digest)) {
                    throw GitHubBackupException("备份摘要格式不受支持，无法安全恢复")
                }
                digest.substringAfter(':').lowercase()
            }
            checkDiskSpace(target.parentFile ?: invalidResponse(), actual.size)
            var url = apiUrl(config, "releases/assets/${item.assetId}")
            var allowToken = true
            // 在请求前打开文件，避免取消清理后回调重新创建文件。
            target.outputStream().use { output ->
                var redirects = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val call = request(config, url, octet = true, authenticated = allowToken)
                    val next = call.read { response ->
                        if (response.code in REDIRECT_CODES) {
                            if (redirects >= 5) throw GitHubBackupException("备份下载重定向超过 5 次，已停止")
                            val location = response.header("Location") ?: invalidResponse()
                            endpoints.redirect(url, location)
                        } else {
                            checkStatus(response, 200)
                            val length = response.body.contentLength()
                            if (length > MAX_ARCHIVE_BYTES || (length >= 0 && length != actual.size)) {
                                throw GitHubBackupException("备份下载大小与附件记录不符")
                            }
                            val hash = MessageDigest.getInstance("SHA-256")
                            var total = 0L
                            response.body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (call.isCanceled()) throw GitHubBackupException("备份下载已取消")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > MAX_ARCHIVE_BYTES || total > actual.size) {
                                        throw GitHubBackupException("备份下载超过允许大小，已停止")
                                    }
                                    output.write(buffer, 0, count)
                                    hash.update(buffer, 0, count)
                                }
                            }
                            if (total != actual.size) throw GitHubBackupException("备份下载不完整，请重试")
                            val digest = hash.digest().joinToString("") { "%02x".format(it) }
                            if (expectedDigest != null && digest != expectedDigest) {
                                throw GitHubBackupException("备份 SHA-256 校验失败，无法恢复")
                            }
                            null
                        }
                    }
                    if (next == null) break
                    // 一旦离开 API origin，后续跳转即使回到 API 也不再携带 PAT。
                    allowToken = allowToken && next.sameOrigin(url)
                    url = next
                    redirects++
                }
            }
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun checkPrivate(config: GitHubBackupConfig) {
        config.validate()
        val repo = json(config, "") as? JsonObject ?: invalidResponse()
        if ((repo["private"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != true) {
            throw GitHubBackupException("仅允许使用私有 GitHub 仓库，请先将仓库设为 private")
        }
    }

    private suspend fun releases(config: GitHubBackupConfig): List<JsonObject> = pages(config, "releases")

    private suspend fun pages(config: GitHubBackupConfig, path: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val seen = mutableSetOf<Long>()
        var page = 1
        while (true) {
            currentCoroutineContext().ensureActive()
            val url = apiUrl(config, path).newBuilder()
                .addQueryParameter("per_page", "100").addQueryParameter("page", page.toString()).build()
            val (entries, hasNext) = request(config, url).read { response ->
                checkStatus(response, 200)
                val array = parseJson(response) as? JsonArray ?: invalidResponse()
                array.map { it as? JsonObject ?: invalidResponse() } to
                    (response.headers.values("Link").any { it.contains("rel=\"next\"") })
            }
            entries.forEach {
                if (!seen.add(it.positiveId())) invalidResponse()
                result += it
            }
            // 不访问 Link 中的 URL，仅使用本地构建的同仓库页码 URL。
            if (entries.isEmpty() || (!hasNext && entries.size < 100)) break
            if (page == Int.MAX_VALUE) invalidResponse()
            page++
        }
        return result
    }

    private suspend fun json(
        config: GitHubBackupConfig,
        path: String,
        body: RequestBody? = null,
        allowConflict: Boolean = false,
        method: String = if (body == null) "GET" else "POST",
    ): JsonElement? = request(config, apiUrl(config, path), body, method = method).read { response ->
        if (allowConflict && response.code == 422) null else {
            checkStatus(response, if (body == null || method == "PATCH") 200 else 201)
            parseJson(response)
        }
    }

    private fun apiUrl(config: GitHubBackupConfig, path: String): HttpUrl = endpoints.api.newBuilder()
        .addPathSegments("repos/${config.owner}/${config.repository}")
        .apply { if (path.isNotEmpty()) addPathSegments(path) }
        .build()

    private fun request(
        config: GitHubBackupConfig,
        url: HttpUrl,
        body: RequestBody? = null,
        octet: Boolean = false,
        authenticated: Boolean = true,
        method: String = if (body == null) "GET" else "POST",
    ): Call {
        val builder = Request.Builder().url(url)
            .header("Accept", if (octet) "application/octet-stream" else "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Beacon-Backup")
            .header("Accept-Encoding", "identity")
        if (authenticated) builder.header("Authorization", "Bearer ${config.token}")
        builder.method(method, body)
        return client.newCall(builder.build())
    }

    private suspend fun <T> Call.read(block: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val activeResponse = AtomicReference<Response?>()
        continuation.invokeOnCancellation {
            cancel()
            runCatching { activeResponse.getAndSet(null)?.close() }
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(safeGitHubError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                activeResponse.set(response)
                try {
                    response.use {
                        if (!continuation.isActive) return
                        val result = block(it)
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(safeGitHubError(error))
                } finally {
                    activeResponse.set(null)
                }
            }
        })
    }

    companion object {
        const val MAX_ARCHIVE_BYTES: Long = 2L * 1024 * 1024 * 1024
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ZIP_TYPE = "application/zip".toMediaType()
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        // 仅支持明确回环地址，测试不能把生产 API 改为任意远程端点。
        internal fun forLocalTests(
            api: HttpUrl,
            upload: HttpUrl = api,
            baseClient: OkHttpClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(),
            now: () -> LocalDateTime = LocalDateTime::now,
        ): GitHubReleaseClient {
            val endpoints = GitHubEndpoints.local(api, upload)
            return GitHubReleaseClient(dedicatedClient(baseClient), endpoints, now)
        }

        private fun dedicatedClient(base: OkHttpClient = OkHttpClient()): OkHttpClient = base.newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .eventListener(EventListener.NONE)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)
            .build()

        private fun uniqueTag(time: LocalDateTime): String =
            "beacon-backup-${time.toLocalDate()}-${time.format(DateTimeFormatter.ofPattern("HHmmss"))}-${UUID.randomUUID().toString().replace("-", "")}"
    }
}

internal class GitHubEndpoints private constructor(
    val api: HttpUrl,
    val upload: HttpUrl,
    private val local: Boolean,
) {
    constructor() : this("https://api.github.com/".toHttpUrl(), "https://uploads.github.com/".toHttpUrl(), false)

    fun redirect(current: HttpUrl, location: String): HttpUrl {
        val raw = runCatching { URI(location) }.getOrNull()
            ?: throw GitHubBackupException("备份下载跳转地址无效")
        if (raw.rawUserInfo != null || raw.rawAuthority?.contains('@') == true || raw.rawFragment != null) {
            throw GitHubBackupException("备份下载跳转包含不允许的凭据或片段")
        }
        val target = current.resolve(location) ?: throw GitHubBackupException("备份下载跳转地址无效")
        val allowed = if (local) target.sameOrigin(api) || target.sameOrigin(upload) else {
            target.scheme == "https" && target.port == 443 &&
                target.host in setOf("api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
        }
        if (!allowed || target.username.isNotEmpty() || target.password.isNotEmpty()) {
            throw GitHubBackupException("备份下载跳转不属于受信任的 HTTPS GitHub 地址，已停止")
        }
        return target
    }

    companion object {
        fun local(api: HttpUrl, upload: HttpUrl): GitHubEndpoints {
            listOf(api, upload).forEach {
                require(it.scheme == "http" && it.host == "127.0.0.1" && it.encodedPath == "/" &&
                    it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
            }
            return GitHubEndpoints(api, upload, true)
        }
    }
}

private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean = scheme == other.scheme && host == other.host && port == other.port

internal fun isBeaconTag(tag: String): Boolean {
    if (!Regex("beacon-backup-\\d{4}-\\d{2}-\\d{2}(?:-[A-Za-z0-9-]+)?").matches(tag) || tag.length > 160) return false
    return runCatching { LocalDate.parse(tag.substring(14, 24)) }.isSuccess
}

internal fun isBeaconZip(name: String): Boolean = name.endsWith(".zip") && isBeaconTag(name.removeSuffix(".zip"))

private fun JsonObject.text(key: String): String = optionalText(key) ?: invalidResponse()
private fun JsonObject.optionalText(key: String): `String`? {
    val value = get(key) ?: return null
    if (value == JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidResponse()
}
private fun JsonObject.isDraft(): Boolean = (get("draft") as? JsonPrimitive)
    ?.takeUnless { it.isString }?.booleanOrNull ?: false
private fun JsonObject.number(key: String): Long = (get(key) as? JsonPrimitive)
    ?.takeUnless { it.isString }?.longOrNull ?: invalidResponse()
private fun JsonObject.positiveId(): Long = number("id").also { if (it <= 0) invalidResponse() }
private fun JsonObject.toItem(tag: String, releaseName: String): GitHubBackupItem = GitHubBackupItem(
    assetId = positiveId(),
    name = text("name"),
    size = number("size").also { if (it < 0) invalidResponse() },
    createdAt = text("created_at"),
    releaseName = releaseName,
    tag = tag,
    digest = optionalText("digest"),
)

private fun parseJson(response: Response): JsonElement {
    val limit = 8 * 1024 * 1024
    if (response.body.contentLength() > limit) invalidResponse()
    val bytes = ByteArrayOutputStream()
    response.body.byteStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > limit) invalidResponse()
            bytes.write(buffer, 0, count)
        }
    }
    return Json.parseToJsonElement(bytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
}

private fun JsonObject.optionalBoolean(key: String): Boolean? {
    val value = get(key) ?: return null
    return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalidResponse()
}

private fun checkConnectStatus(
    response: Response,
    expected: Int,
    creating: Boolean = false,
    afterConflict: Boolean = false,
) {
    if (response.code == expected) return
    val conflict = if (afterConflict) "创建返回 HTTP 422，同名仓库可能已存在但对当前 Token 不可见，或创建权限不足；" else ""
    val reason = when (response.code) {
        401 -> "GitHub Token 错误、已失效或已撤销，请更换 Token"
        403 -> if (creating) {
            "GitHub 拒绝创建私有仓库，请检查 Administration: Read and write、Repository access: All repositories（仅缺库自动创建时需要广范围授权），以及备份所需的 Contents: Read and write；也可能触发 API 限流"
        } else {
            "GitHub 拒绝访问，请检查 Repository access 是否包含 beacon-backup，以及 Contents: Read and write，或稍后重试以避开 API 限流；仅仓库缺失且需自动创建时才需要 Administration: Read and write 和 Repository access: All repositories"
        }
        404 -> if (afterConflict) {
            "无法访问目标仓库，请检查同名私库是否已存在但不在 Repository access 授权范围内，以及 Token 的访问和创建权限"
        } else if (creating) {
            "GitHub 创建仓库接口不可访问，请检查 Token 的账号访问与创建权限"
        } else {
            "GitHub 账号或目标私有仓库不可访问，可能不存在或 Token 无权访问，请检查 Repository access 授权范围"
        }
        422 -> "GitHub 拒绝创建或访问目标仓库，同名仓库可能已存在但不可见，或仓库状态、创建权限不满足要求，请检查 Repository access 和 Administration 权限"
        429 -> "GitHub 请求过于频繁，已触发限流，请稍后重试"
        in 300..399 -> "GitHub API 返回不允许的重定向，已停止连接"
        in 500..599 -> "GitHub 服务暂时不可用，请稍后重试；仓库可能已创建，请勿删除已有仓库"
        else -> "GitHub 连接请求失败，请检查账号与仓库权限"
    }
    throw GitHubBackupException("$conflict$reason（HTTP ${response.code}）；未修改或删除已有仓库")
}

private fun checkStatus(response: Response, expected: Int) {
    if (response.code == expected) return
    val reason = when (response.code) {
        401 -> "GitHub Token 错误或已失效"
        403 -> "GitHub 拒绝访问，请检查私库 Contents 读写权限或 API 限流"
        404 -> "私有仓库或备份不存在，或 Token 无权访问"
        409, 422 -> "仓库状态或备份版本冲突，请检查仓库是否已初始化后重试"
        429 -> "GitHub 请求受限，请稍后重试"
        in 300..399 -> "GitHub API 返回不允许的重定向"
        in 500..599 -> "GitHub 服务暂时不可用，请稍后重试"
        else -> "GitHub 备份请求失败"
    }
    throw GitHubBackupException("$reason（HTTP ${response.code}）；已有备份未改变")
}

private fun invalidResponse(): Nothing = throw GitHubBackupException("GitHub 备份响应无效，请刷新后重试")

internal fun validateDownloadSize(size: Long) {
    if (size !in 1..GitHubReleaseClient.MAX_ARCHIVE_BYTES) throw GitHubBackupException("备份 ZIP 为空或超过 2 GiB，无法恢复")
}

internal fun checkDiskSpace(directory: File, size: Long) {
    if (!directory.isDirectory || directory.usableSpace < size + 16L * 1024 * 1024) {
        throw GitHubBackupException("本地磁盘空间不足，无法下载备份")
    }
}

internal fun safeGitHubError(error: Exception): GitHubBackupException = when (error) {
    is GitHubBackupException -> error
    is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> GitHubBackupException("GitHub 备份请求超时，请检查网络后重试")
    is javax.net.ssl.SSLException -> GitHubBackupException("GitHub TLS 连接失败，请检查网络和证书")
    is IOException -> GitHubBackupException("GitHub 备份网络或本地文件操作失败，请检查网络和磁盘空间")
    else -> GitHubBackupException("GitHub 备份操作失败，请检查配置或备份文件后重试")
}

internal suspend fun <T> safeGitHubOperation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw safeGitHubError(error)
    }
}
