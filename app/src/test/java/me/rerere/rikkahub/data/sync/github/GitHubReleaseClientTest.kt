package me.rerere.rikkahub.data.sync.github

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GitHubReleaseClientTest {
    private val config = GitHubBackupConfig("owner", "private-repo", "test-secret-pat")
    private val tag = "beacon-backup-2026-11-21"
    private val bytes = byteArrayOf(80, 75, 3, 4, 0, -1, 42)

    @Test
    fun `连接登录账号已有私库只GET且trim令牌并隔离应用拦截器`() = runBlocking {
        val requests = CopyOnWriteArrayList<RequestSnapshot>()
        LocalServer { exchange ->
            requests += exchange.snapshot()
            when (exchange.requestURI.path) {
                "/user" -> exchange.respond(200, """{"login":"Login-User"}""")
                "/repos/Login-User/beacon-backup" -> exchange.respond(
                    200,
                    """{"private":true,"name":"beacon-backup","owner":{"login":"login-user"},"archived":false,"disabled":false,"permissions":{"push":true}}""",
                )
                else -> exchange.respond(500, "不得创建或修改已有仓库")
            }
        }.use { server ->
            val base = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .addInterceptor { error("连接不得进入应用拦截器") }
                .addNetworkInterceptor { error("连接不得进入网络拦截器") }.build()
            val client = GitHubReleaseClient.forLocalTests(server.url.toHttpUrl(), baseClient = base)
            val result = client.connect(" \n${config.token}\t ")
            assertEquals("Login-User", result.owner)
            assertEquals("beacon-backup", result.repository)
            assertEquals(config.token, result.token)
            assertFalse(result.toString().contains(config.token))
            assertEquals(listOf("/user", "/repos/Login-User/beacon-backup"), requests.map { it.path })
            requests.forEach {
                assertEquals("GET", it.method)
                assertEquals("Bearer ${config.token}", it.authorization)
                assertEquals("2022-11-28", it.version)
                assertEquals("application/vnd.github+json", it.accept)
                assertNull(it.query)
            }
        }
    }

    @Test
    fun `连接兼容缺少身份状态与permissions字段但不据此声称已验证写权限`() = runBlocking {
        val body = AtomicReference("""{"private":true}""")
        val operations = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            operations += "${exchange.requestMethod} ${exchange.requestURI.path}"
            if (exchange.requestURI.path == "/user") exchange.respond(200, """{"login":"owner"}""")
            else exchange.respond(200, body.get())
        }.use { server ->
            listOf("""{"private":true}""", """{"private":true,"owner":{},"permissions":{}}""").forEach {
                body.set(it)
                assertEquals("owner", server.client().connect(config.token).owner)
            }
            assertEquals(4, operations.size)
            assertTrue(operations.all { it.startsWith("GET ") })
        }
    }

    @Test
    fun `缺库404仅创建private且auto_init的固定备份仓库并再次GET确认`() = runBlocking {
        val operations = CopyOnWriteArrayList<String>()
        val payload = AtomicReference<JsonObject>()
        val repositoryReads = AtomicInteger()
        LocalServer { exchange ->
            operations += "${exchange.requestMethod} ${exchange.requestURI.path}"
            when (exchange.requestURI.path) {
                "/user" -> exchange.respond(200, """{"login":"owner"}""")
                "/repos/owner/beacon-backup" -> exchange.respond(
                    if (repositoryReads.incrementAndGet() == 1) 404 else 200, """{"private":true}""",
                )
                "/user/repos" -> {
                    assertEquals("POST", exchange.requestMethod)
                    assertEquals("application/json; charset=utf-8", exchange.requestHeaders.getFirst("Content-Type"))
                    payload.set(Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject)
                    exchange.respond(201, """{"private":true,"name":"beacon-backup","owner":{"login":"owner"}}""")
                }
                else -> exchange.respond(500, "不允许其他操作")
            }
        }.use { server ->
            val result = server.client().connect(config.token)
            assertEquals("owner", result.owner)
            assertEquals("beacon-backup", result.repository)
            assertEquals(config.token, result.token)
            assertEquals(setOf("name", "private", "auto_init", "description"), payload.get().keys)
            assertEquals(JsonPrimitive("beacon-backup"), payload.get()["name"])
            assertEquals(JsonPrimitive(true), payload.get()["private"])
            assertEquals(JsonPrimitive(true), payload.get()["auto_init"])
            assertTrue(payload.get().getValue("description").jsonPrimitive.content.isNotBlank())
            assertEquals(
                listOf("GET /user", "GET /repos/owner/beacon-backup", "POST /user/repos", "GET /repos/owner/beacon-backup"),
                operations,
            )
        }
    }

    @Test
    fun `已有公开仓库身份不符归档禁用或明确不可push均拒绝且不修改仓库`() = runBlocking {
        val body = AtomicReference("{}")
        val methods = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            methods += exchange.requestMethod
            if (exchange.requestURI.path == "/user") exchange.respond(200, """{"login":"owner"}""")
            else exchange.respond(200, body.get())
        }.use { server ->
            listOf(
                """{"private":false}""" to "私有",
                "{}" to "私有",
                """{"private":"true"}""" to "响应无效",
                """{"private":true,"name":"another-repo"}""" to "名称",
                """{"private":true,"owner":{"login":"another-owner"}}""" to "所有者",
                """{"private":true,"archived":true}""" to "归档",
                """{"private":true,"disabled":true}""" to "禁用",
                """{"private":true,"permissions":{"push":false}}""" to "Contents",
            ).forEach { (value, message) ->
                body.set(value)
                assertSafeFailure(message) { server.client().connect(config.token) }
            }
            assertEquals(16, methods.size)
            assertTrue(methods.all { it == "GET" })
        }
    }

    @Test
    fun `连接每阶段401和403及429提示具体且不泄漏响应令牌或异常链`() = runBlocking {
        for (stage in listOf("login", "repository", "create", "confirm")) {
            val status = AtomicInteger(401)
            val reads = AtomicInteger()
            val operations = CopyOnWriteArrayList<String>()
            LocalServer { exchange ->
                operations += exchange.requestMethod
                when {
                    exchange.requestURI.path == "/user" && stage != "login" ->
                        exchange.respond(200, """{"login":"owner"}""")
                    exchange.requestURI.path == "/repos/owner/beacon-backup" && stage in listOf("create", "confirm") && reads.incrementAndGet() == 1 ->
                        exchange.respond(404, "private-response-body ${config.token}")
                    exchange.requestURI.path == "/user/repos" && stage == "confirm" ->
                        exchange.respond(201, """{"private":true}""")
                    else -> exchange.respond(status.get(), "private-response-body ${config.token}")
                }
            }.use { server ->
                for (code in listOf(401, 403, 429)) {
                    reads.set(0)
                    status.set(code)
                    val messages = when (code) {
                        401 -> arrayOf("HTTP 401", "Token")
                        403 -> if (stage == "create") {
                            arrayOf("HTTP 403", "Administration", "Read and write", "Repository access", "All repositories", "Contents", "仅缺库")
                        } else arrayOf("HTTP 403", "Contents", "Repository access")
                        else -> arrayOf("HTTP 429", "限流")
                    }
                    assertSafeFailure(*messages) { server.client().connect(config.token) }
                }
                assertTrue(operations.all { it == "GET" || it == "POST" })
            }
        }
    }

    @Test
    fun `创建422竞争后仅在重查到可用私库时成功`() = runBlocking {
        val operations = CopyOnWriteArrayList<String>()
        val reads = AtomicInteger()
        LocalServer { exchange ->
            operations += "${exchange.requestMethod} ${exchange.requestURI.path}"
            when (exchange.requestURI.path) {
                "/user" -> exchange.respond(200, """{"login":"owner"}""")
                "/user/repos" -> exchange.respond(422, "private-response-body ${config.token}")
                else -> exchange.respond(
                    if (reads.incrementAndGet() == 1) 404 else 200,
                    """{"private":true,"name":"beacon-backup","owner":{"login":"owner"},"permissions":{"push":true}}""",
                )
            }
        }.use { server ->
            assertEquals("owner", server.client().connect(config.token).owner)
            assertEquals(
                listOf("GET /user", "GET /repos/owner/beacon-backup", "POST /user/repos", "GET /repos/owner/beacon-backup"),
                operations,
            )
        }
    }

    @Test
    fun `创建422重查仍不可见或权限不足时说明同名仓库与权限且不重建删除`() = runBlocking {
        val status = AtomicInteger(404)
        val reads = AtomicInteger()
        val methods = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            methods += exchange.requestMethod
            when (exchange.requestURI.path) {
                "/user" -> exchange.respond(200, """{"login":"owner"}""")
                "/user/repos" -> exchange.respond(422, "private-response-body ${config.token}")
                else -> exchange.respond(if (reads.incrementAndGet() == 1) 404 else status.get(), "private-response-body ${config.token}")
            }
        }.use { server ->
            for (code in listOf(404, 403, 401, 422, 429)) {
                reads.set(0)
                methods.clear()
                status.set(code)
                assertSafeFailure("HTTP 422", "同名", "不可见", "权限", "HTTP $code") { server.client().connect(config.token) }
                assertEquals(listOf("GET", "GET", "POST", "GET"), methods)
            }
        }
    }

    @Test
    fun `创建422竞争得到公开归档错误身份或不可push仓库也不能成功`() = runBlocking {
        val body = AtomicReference("{}")
        val reads = AtomicInteger()
        val methods = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            methods += exchange.requestMethod
            when (exchange.requestURI.path) {
                "/user" -> exchange.respond(200, """{"login":"owner"}""")
                "/user/repos" -> exchange.respond(422, "private-response-body ${config.token}")
                else -> exchange.respond(if (reads.incrementAndGet() == 1) 404 else 200, body.get())
            }
        }.use { server ->
            listOf(
                """{"private":false}""", """{"private":true,"archived":true}""",
                """{"private":true,"name":"other"}""", """{"private":true,"owner":{"login":"other"}}""",
                """{"private":true,"permissions":{"push":false}}""",
            ).forEach {
                reads.set(0)
                methods.clear()
                body.set(it)
                assertSafeFailure { server.client().connect(config.token) }
                assertEquals(listOf("GET", "GET", "POST", "GET"), methods)
            }
        }
    }

    @Test
    fun `非法login在访问仓库和建库之前拒绝且错误不回显登录字段`() = runBlocking {
        val login = AtomicReference("../owner")
        val paths = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            paths += exchange.requestURI.path
            exchange.respond(200, JsonObject(mapOf("login" to JsonPrimitive(login.get()))).toString())
        }.use { server ->
            listOf("../owner", "owner/repo", "-owner", "owner-", "a--b", "a".repeat(40), "", "账号", config.token + "/").forEach {
                login.set(it)
                assertSafeFailure("登录名无效") { server.client().connect(config.token) }
            }
            assertEquals(9, paths.size)
            assertTrue(paths.all { it == "/user" })
        }
    }

    @Test
    fun `非法token在任何网络请求前拒绝`() = runBlocking {
        val requests = AtomicInteger()
        LocalServer {
            requests.incrementAndGet()
            it.respond(500, "不应访问网络")
        }.use { server ->
            listOf("", " \t\n ", "bad token", "bad\ntoken", "令牌", "a".repeat(1025)).forEach {
                assertSafeFailure("Token 无效") { server.client().connect(it) }
            }
            assertEquals(0, requests.get())
        }
    }

    @Test
    fun `建库201必须验证私有与目标身份再GET且确认失败不能返回配置`() = runBlocking {
        val body = AtomicReference("{}")
        val confirmStatus = AtomicInteger(200)
        val confirmBody = AtomicReference("""{"private":true}""")
        val reads = AtomicInteger()
        val methods = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            methods += exchange.requestMethod
            when (exchange.requestURI.path) {
                "/user" -> exchange.respond(200, """{"login":"owner"}""")
                "/user/repos" -> exchange.respond(201, body.get())
                else -> {
                    if (reads.incrementAndGet() == 1) exchange.respond(404, "")
                    else exchange.respond(confirmStatus.get(), confirmBody.get())
                }
            }
        }.use { server ->
            listOf(
                """{"private":false}""", "{}", """{"private":true,"name":"other"}""",
                """{"private":true,"owner":{"login":"other"}}""",
            ).forEach {
                reads.set(0)
                methods.clear()
                body.set(it)
                assertSafeFailure { server.client().connect(config.token) }
                assertEquals(listOf("GET", "GET", "POST"), methods)
            }
            body.set("""{"private":true}""")
            for (status in listOf(404, 500, 200)) {
                reads.set(0)
                methods.clear()
                confirmStatus.set(status)
                confirmBody.set("""{"private":false}""")
                assertSafeFailure { server.client().connect(config.token) }
                assertEquals(listOf("GET", "GET", "POST", "GET"), methods)
            }
        }
    }

    @Test
    fun `POST服务器失败不重试不删除或修改已有仓库且脱敏`() = runBlocking {
        val methods = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            methods += exchange.requestMethod
            when (exchange.requestURI.path) {
                "/user" -> exchange.respond(200, """{"login":"owner"}""")
                "/repos/owner/beacon-backup" -> exchange.respond(404, "private-response-body ${config.token}")
                else -> exchange.respond(500, "private-response-body ${config.token}")
            }
        }.use { server ->
            assertSafeFailure("HTTP 500", "服务暂时不可用", "请勿删除") { server.client().connect(config.token) }
            assertEquals(listOf("GET", "GET", "POST"), methods)
        }
    }

    @Test
    fun `连接不跟随API重定向且login404不是仓库缺失不能建库`() = runBlocking {
        val status = AtomicInteger(302)
        val paths = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            paths += exchange.requestURI.path
            exchange.responseHeaders.add("Location", "/other")
            exchange.respond(status.get(), "private-response-body ${config.token}")
        }.use { server ->
            assertSafeFailure("重定向") { server.client().connect(config.token) }
            status.set(404)
            assertSafeFailure("HTTP 404", "无权访问") { server.client().connect(config.token) }
            assertEquals(listOf("/user", "/user"), paths)
        }
    }

    @Test
    fun `取消连接各阶段的响应头或响应体等待会取消Call且不继续建库或清理仓库`() = runBlocking {
        for (stage in listOf("/user", "/repos/owner/beacon-backup", "/user/repos", "confirm")) {
            for (streaming in listOf(false, true)) {
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val dispatcher = okhttp3.Dispatcher()
                val methods = CopyOnWriteArrayList<String>()
                val reads = AtomicInteger()
                LocalServer { exchange ->
                    methods += exchange.requestMethod
                    val path = exchange.requestURI.path
                    val isRepository = path == "/repos/owner/beacon-backup"
                    val firstRead = isRepository && reads.incrementAndGet() == 1
                    when {
                        path == "/user" && stage != "/user" -> exchange.respond(200, """{"login":"owner"}""")
                        firstRead && stage in listOf("/user/repos", "confirm") -> exchange.respond(404, "")
                        path == "/user/repos" && stage == "confirm" -> exchange.respond(201, """{"private":true}""")
                        else -> {
                            if (streaming) {
                                exchange.sendResponseHeaders(if (path == "/user/repos") 201 else 200, 0)
                                exchange.responseBody.write('{'.code)
                                exchange.responseBody.flush()
                            }
                            started.countDown()
                            release.await(10, TimeUnit.SECONDS)
                            exchange.close()
                        }
                    }
                }.use { server ->
                    try {
                        val base = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dispatcher(dispatcher).build()
                        val client = GitHubReleaseClient.forLocalTests(server.url.toHttpUrl(), baseClient = base)
                        kotlinx.coroutines.coroutineScope {
                            val pending = async { client.connect(config.token) }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                assertTrue(started.await(5, TimeUnit.SECONDS))
                            }
                            val call = dispatcher.runningCalls().single()
                            withTimeout(5000) { pending.cancelAndJoin() }
                            assertTrue(call.isCanceled())
                            assertTrue(pending.isCancelled)
                            val expected = when (stage) {
                                "/user" -> listOf("GET")
                                "/repos/owner/beacon-backup" -> listOf("GET", "GET")
                                "/user/repos" -> listOf("GET", "GET", "POST")
                                else -> listOf("GET", "GET", "POST", "GET")
                            }
                            assertEquals(expected, methods)
                        }
                    } finally {
                        release.countDown()
                    }
                }
            }
        }
    }

    @Test
    fun `仅回环测试端点可注入且禁止凭据查询和任意远程服务`() {
        listOf(
            "http://example.com/", "https://127.0.0.1/", "http://localhost/",
            "http://127.0.0.1/path/", "http://user@127.0.0.1/",
            "http://127.0.0.1/?token=secret", "http://127.0.0.1/#fragment",
        ).forEach { url ->
            assertTrue(url, runCatching { GitHubReleaseClient.forLocalTests(url.toHttpUrl()) }.isFailure)
        }
    }

    @Test
    fun `生产下载跳转仅允许明确GitHub HTTPS域且拒绝用户信息降级和伪后缀`() {
        val endpoints = GitHubEndpoints()
        val api = "https://api.github.com/repos/o/r/releases/assets/1".toHttpUrl()
        listOf(
            "https://release-assets.githubusercontent.com/signed?signature=test",
            "https://objects.githubusercontent.com/path", "https://github-releases.githubusercontent.com/path",
            "/repos/o/r/releases/assets/1",
        ).forEach { assertEquals(api.resolve(it), endpoints.redirect(api, it)) }
        listOf(
            "http://release-assets.githubusercontent.com/path",
            "https://release-assets.githubusercontent.com.evil.invalid/path",
            "https://evil.githubusercontent.com/path", "https://github.com/path",
            "https://uploads.github.com/path", "https://api.github.com:444/path",
            "https://user:secret@api.github.com/path", "https://@api.github.com/path",
            "//user@objects.githubusercontent.com/path", "https://objects.githubusercontent.com/path#secret",
            "https://127.0.0.1/path", "file:///tmp/backup.zip",
        ).forEach { location ->
            val error = runCatching { endpoints.redirect(api, location) }.exceptionOrNull()
            assertTrue(location, error is GitHubBackupException)
            assertFalse(error.toString().contains("secret"))
            assertNull(error?.cause)
        }
    }

    @Test
    fun `私库测试使用明确API版本并隔离拦截器且不跟随API重定向`() = runBlocking {
        val seen = AtomicReference<RequestSnapshot>()
        val status = AtomicInteger(200)
        val followed = AtomicInteger()
        LocalServer { exchange ->
            seen.set(exchange.snapshot())
            if (exchange.requestURI.path == "/other") followed.incrementAndGet()
            exchange.responseHeaders.add("Location", "/other")
            exchange.respond(status.get(), """{"private":true}""")
        }.use { server ->
            val base = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .addInterceptor { error("不得进入应用拦截器") }
                .addNetworkInterceptor { error("不得进入网络拦截器") }.build()
            val client = GitHubReleaseClient.forLocalTests(server.url.toHttpUrl(), baseClient = base)
            client.test(config)
            assertEquals("/repos/owner/private-repo", seen.get().path)
            assertEquals("Bearer ${config.token}", seen.get().authorization)
            assertEquals("2022-11-28", seen.get().version)
            assertEquals("application/vnd.github+json", seen.get().accept)
            status.set(302)
            assertSafeFailure { client.test(config) }
            assertEquals(0, followed.get())
        }
    }

    @Test
    fun `公有仓库和未明确声明私有的仓库禁止后续操作`() = runBlocking {
        val body = AtomicReference("""{"private":false}""")
        val requests = AtomicInteger()
        LocalServer {
            requests.incrementAndGet()
            it.respond(200, body.get())
        }.use { server ->
            val client = server.client()
            listOf("""{"private":false}""", "{}", """{"private":"true"}""").forEach {
                body.set(it)
                assertSafeFailure { client.list(config) }
            }
            assertEquals(3, requests.get())
        }
    }

    @Test
    fun `完整遍历Release与附件分页且只列出Beacon日期ZIP`() = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            paths += exchange.requestURI.toString()
            val page = exchange.requestURI.rawQuery.orEmpty()
            when (exchange.requestURI.path) {
                "/repos/owner/private-repo" -> exchange.respond(200, """{"private":true}""")
                "/repos/owner/private-repo/releases" -> {
                    if (page.split('&').contains("page=1")) {
                        // 故意给出外站 Link：客户端只能本地构建同仓库下一页。
                        exchange.responseHeaders.add("Link", "<https://do-not-connect.invalid/?page=2>; rel=\"next\"")
                        exchange.respond(200, "[${release(1, tag)},${release(2, "unrelated") }]")
                    } else exchange.respond(200, "[${release(3, "$tag-120000-abc")}]")
                }
                "/repos/owner/private-repo/releases/1/assets" -> {
                    if (page.split('&').contains("page=1")) {
                        exchange.responseHeaders.add("Link", "<https://do-not-connect.invalid/>; rel=\"next\"")
                        exchange.respond(200, "[${asset(10)},${asset(11, "unrelated.zip")},${asset(12, "beacon-backup-not-date.zip")}]")
                    } else exchange.respond(200, "[${asset(13, "$tag-120001-xyz.zip")}]")
                }
                "/repos/owner/private-repo/releases/3/assets" -> exchange.respond(200, "[${asset(14, "$tag-120000-abc.zip")}]")
                else -> exchange.respond(500, "不应访问此路径")
            }
        }.use { server ->
            val result = server.client().list(config)
            assertEquals(setOf(10L, 13L, 14L), result.map { it.assetId }.toSet())
            assertEquals(tag, result.first { it.assetId == 10L }.releaseName)
            assertTrue(paths.contains("/repos/owner/private-repo/releases?per_page=100&page=2"))
            assertTrue(paths.contains("/repos/owner/private-repo/releases/1/assets?per_page=100&page=2"))
            assertFalse(paths.any { it.contains("releases/2/assets") })
        }
    }

    @Test
    fun `列表排除同日期tag草稿且不请求其附件`() = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            paths += exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/repos/owner/private-repo" -> exchange.respond(200, """{"private":true}""")
                "/repos/owner/private-repo/releases" -> exchange.respond(
                    200, "[${release(1, tag, draft = true)},${release(2, "$tag-published")}]",
                )
                "/repos/owner/private-repo/releases/2/assets" -> exchange.respond(200, "[${asset(2, "$tag-published.zip")}]")
                else -> exchange.respond(500, "不应访问草稿附件")
            }
        }.use { server ->
            val result = server.client().list(config)
            assertEquals(listOf(2L), result.map { it.assetId })
            assertFalse(paths.contains("/repos/owner/private-repo/releases/1/assets"))
            assertTrue(paths.contains("/repos/owner/private-repo/releases/2/assets"))
        }
    }

    @Test
    fun `首次使用日期tag重复备份独立Release并原样上传ZIP字节`() = runBlocking {
        val existing = CopyOnWriteArrayList<String>()
        val created = CopyOnWriteArrayList<String>()
        val operations = CopyOnWriteArrayList<String>()
        val uploaded = CopyOnWriteArrayList<RequestSnapshot>()
        val uploadedBytes = CopyOnWriteArrayList<ByteArray>()
        LocalServer { exchange ->
            when {
                exchange.requestURI.path == "/repos/owner/private-repo" -> exchange.respond(200, """{"private":true}""")
                exchange.requestURI.path.endsWith("/releases") && exchange.requestMethod == "GET" ->
                    exchange.respond(200, existing.mapIndexed { index, value -> release(index + 1L, value) }.joinToString(",", "[", "]"))
                exchange.requestURI.path.endsWith("/releases") && exchange.requestMethod == "POST" -> {
                    val payload = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    val value = payload.getValue("tag_name").jsonPrimitive.content
                    assertEquals(value, payload.getValue("name").jsonPrimitive.content)
                    assertEquals(JsonPrimitive(true), payload.getValue("draft"))
                    operations += "create"
                    created += value
                    existing += value
                    exchange.respond(201, """{"id":${created.size}}""")
                }
                exchange.requestMethod == "PATCH" -> {
                    assertEquals("/repos/owner/private-repo/releases/${created.size}", exchange.requestURI.path)
                    assertEquals("application/json; charset=utf-8", exchange.requestHeaders.getFirst("Content-Type"))
                    val payload = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    assertEquals(Json.parseToJsonElement("""{"draft":false,"make_latest":"false"}"""), payload)
                    operations += "publish"
                    exchange.respond(200, release(created.size.toLong(), created.last()))
                }
                exchange.requestURI.path.endsWith("/assets") -> {
                    operations += "upload"
                    uploaded += exchange.snapshot()
                    uploadedBytes += exchange.requestBody.readBytes()
                    exchange.respond(201, asset(created.size.toLong(), "${created.last()}.zip"))
                }
                else -> exchange.respond(500, "不允许删除或覆盖")
            }
        }.use { server ->
            val file = File.createTempFile("github-upload-test-", ".zip")
            try {
                file.writeBytes(bytes)
                val client = server.client()
                val first = client.upload(config, file)
                val second = client.upload(config, file)
                val third = client.upload(config, file)
                assertEquals(tag, first.tag)
                assertTrue(second.tag.startsWith("$tag-123456-"))
                assertNotEquals(second.tag, third.tag)
                assertEquals(3, existing.size)
                assertEquals(List(3) { listOf("create", "upload", "publish") }.flatten(), operations)
                uploaded.forEachIndexed { index, request ->
                    assertEquals("POST", request.method)
                    assertEquals("application/zip", request.contentType)
                    assertEquals("name=${created[index]}.zip", request.query)
                    assertEquals("Bearer ${config.token}", request.authorization)
                    assertArrayEquals(bytes, uploadedBytes[index])
                }
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `并发tag冲突只新建带随机后缀Release`() = runBlocking {
        val attempts = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            when {
                exchange.requestURI.path == "/repos/owner/private-repo" -> exchange.respond(200, """{"private":true}""")
                exchange.requestMethod == "GET" -> exchange.respond(200, "[]")
                exchange.requestURI.path.endsWith("/releases") -> {
                    val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    attempts += body.getValue("tag_name").jsonPrimitive.content
                    exchange.respond(if (attempts.size == 1) 422 else 201, """{"id":2}""")
                }
                exchange.requestMethod == "PATCH" -> exchange.respond(200, """{"id":2}""")
                else -> exchange.respond(201, asset(1, "${attempts.last()}.zip"))
            }
        }.use { server ->
            val file = File.createTempFile("github-upload-test-", ".zip")
            try {
                file.writeBytes(bytes)
                val result = server.client().upload(config, file)
                assertEquals(tag, attempts.first())
                assertTrue(result.tag.startsWith("$tag-123456-"))
                assertEquals(2, attempts.size)
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `上传失败保留已有备份且错误不泄漏响应正文Token或异常链`() = runBlocking {
        val methods = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            methods += exchange.requestMethod
            when {
                exchange.requestURI.path == "/repos/owner/private-repo" -> exchange.respond(200, """{"private":true}""")
                exchange.requestMethod == "GET" -> exchange.respond(200, "[${release(1, tag)}]")
                exchange.requestURI.path.endsWith("/releases") -> exchange.respond(201, """{"id":2}""")
                else -> exchange.respond(500, "private-response-body ${config.token}")
            }
        }.use { server ->
            val file = File.createTempFile("github-upload-test-", ".zip")
            try {
                file.writeBytes(bytes)
                assertSafeFailure { server.client().upload(config, file) }
                assertFalse(methods.contains("DELETE"))
                assertFalse(methods.contains("PATCH"))
                assertTrue(file.exists())
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `发布失败不返回备份条目且不删除已有备份并脱敏错误`() = runBlocking {
        val operations = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            operations += "${exchange.requestMethod} ${exchange.requestURI.path}"
            when {
                exchange.requestURI.path == "/repos/owner/private-repo" -> exchange.respond(200, """{"private":true}""")
                exchange.requestMethod == "GET" -> exchange.respond(200, "[${release(1, tag)}]")
                exchange.requestURI.path.endsWith("/releases") && exchange.requestMethod == "POST" -> {
                    val payload = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    assertEquals(JsonPrimitive(true), payload.getValue("draft"))
                    exchange.respond(201, """{"id":2,"draft":true}""")
                }
                exchange.requestURI.path == "/repos/owner/private-repo/releases/2/assets" && exchange.requestMethod == "POST" -> {
                    val name = exchange.requestURI.rawQuery.substringAfter("name=")
                    exchange.respond(201, asset(2, name))
                }
                exchange.requestURI.path == "/repos/owner/private-repo/releases/2" && exchange.requestMethod == "PATCH" -> {
                    val payload = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    assertEquals(Json.parseToJsonElement("""{"draft":false,"make_latest":"false"}"""), payload)
                    exchange.respond(500, "private-response-body ${config.token}")
                }
                else -> exchange.respond(500, "不允许删除或覆盖")
            }
        }.use { server ->
            val file = File.createTempFile("github-upload-test-", ".zip")
            try {
                file.writeBytes(bytes)
                assertSafeFailure { server.client().upload(config, file) }
                assertEquals(
                    listOf(
                        "GET /repos/owner/private-repo",
                        "GET /repos/owner/private-repo/releases",
                        "POST /repos/owner/private-repo/releases",
                        "POST /repos/owner/private-repo/releases/2/assets",
                        "PATCH /repos/owner/private-repo/releases/2",
                    ),
                    operations,
                )
                assertTrue(file.exists())
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `私有附件从API请求octet并校验大小与SHA256且消费后清理临时文件`() = runBlocking {
        val accept = AtomicReference<String>()
        LocalServer { exchange ->
            standardDownload(exchange) {
                accept.set(exchange.requestHeaders.getFirst("Accept"))
                exchange.respond(200, bytes)
            }
        }.use { server ->
            withDirectory { directory ->
                val value = withDownloadedBackup(server.client(), config, item(), directory) { archive ->
                    assertArrayEquals(bytes, archive.readBytes())
                    "staged"
                }
                assertEquals("staged", value)
                assertEquals("application/octet-stream", accept.get())
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        }
    }

    @Test
    fun `跨origin跳转不携带PAT且返回API后也不恢复PAT`() = runBlocking {
        val cdnAuth = AtomicReference<String>()
        val returnedAuth = AtomicReference<String>()
        val returnUrl = AtomicReference<String>()
        LocalServer { exchange ->
            cdnAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.responseHeaders.add("Location", returnUrl.get())
            exchange.respond(302, "")
        }.use { cdn ->
            LocalServer { exchange ->
                if (exchange.requestURI.path == "/final") {
                    returnedAuth.set(exchange.requestHeaders.getFirst("Authorization"))
                    exchange.respond(200, bytes)
                } else standardDownload(exchange) {
                    assertEquals("Bearer ${config.token}", exchange.requestHeaders.getFirst("Authorization"))
                    exchange.responseHeaders.add("Location", "${cdn.url}download")
                    exchange.respond(302, "")
                }
            }.use { api ->
                returnUrl.set("${api.url}final")
                val client = GitHubReleaseClient.forLocalTests(api.url.toHttpUrl(), cdn.url.toHttpUrl())
                withDirectory { directory ->
                    withDownloadedBackup(client, config, item(), directory) { assertArrayEquals(bytes, it.readBytes()) }
                    assertTrue(directory.listFiles()!!.isEmpty())
                }
                assertNull(cdnAuth.get())
                assertNull(returnedAuth.get())
            }
        }
    }

    @Test
    fun `下载最多五跳且禁止未授权目标`() = runBlocking {
        val count = AtomicInteger()
        val location = AtomicReference("/loop")
        LocalServer { exchange ->
            if (exchange.requestURI.path == "/loop") {
                count.incrementAndGet()
                exchange.responseHeaders.add("Location", location.get())
                exchange.respond(302, "")
            } else standardDownload(exchange) {
                count.incrementAndGet()
                exchange.responseHeaders.add("Location", location.get())
                exchange.respond(302, "")
            }
        }.use { server ->
            withDirectory { directory ->
                assertSafeFailure { withDownloadedBackup(server.client(), config, item(), directory) {} }
                assertEquals(6, count.get())
                assertTrue(directory.listFiles()!!.isEmpty())
                location.set("http://127.0.0.1:1/untrusted")
                count.set(0)
                assertSafeFailure { withDownloadedBackup(server.client(), config, item(), directory) {} }
                assertEquals(1, count.get())
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        }
    }

    @Test
    fun `恰好五次合法跳转可以下载`() = runBlocking {
        val count = AtomicInteger()
        LocalServer { exchange ->
            fun sendDownload() {
                if (count.getAndIncrement() < 5) {
                    exchange.responseHeaders.add("Location", "/redirect")
                    exchange.respond(302, "")
                } else exchange.respond(200, bytes)
            }
            if (exchange.requestURI.path == "/redirect") sendDownload()
            else standardDownload(exchange) { sendDownload() }
        }.use { server ->
            withDirectory { directory ->
                withDownloadedBackup(server.client(), config, item(), directory) { assertArrayEquals(bytes, it.readBytes()) }
                assertEquals(6, count.get())
            }
        }
    }

    @Test
    fun `未知长度流拒绝超出记录大小并清理失败文件`() = runBlocking {
        LocalServer { exchange ->
            standardDownload(exchange) {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(bytes + byteArrayOf(1)) }
                exchange.close()
            }
        }.use { server ->
            withDirectory { directory ->
                assertSafeFailure { withDownloadedBackup(server.client(), config, item(), directory) { error("不得暂存") } }
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        }
    }

    @Test
    fun `摘要不符大小不符短流和损坏摘要不允许交给恢复层`() = runBlocking {
        val metadata = AtomicReference(asset())
        val data = AtomicReference(bytes)
        val chunked = AtomicInteger(0)
        LocalServer { exchange ->
            when {
                exchange.requestURI.path == "/repos/owner/private-repo" -> exchange.respond(200, """{"private":true}""")
                exchange.requestHeaders.getFirst("Accept") == "application/vnd.github+json" -> exchange.respond(200, metadata.get())
                chunked.get() == 1 -> {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { it.write(data.get()) }
                    exchange.close()
                }
                else -> exchange.respond(200, data.get())
            }
        }.use { server ->
            withDirectory { directory ->
                val client = server.client()
                data.set(bytes.map { (it + 1).toByte() }.toByteArray())
                assertSafeFailure { withDownloadedBackup(client, config, item(), directory) { error("不得暂存") } }
                data.set(bytes.copyOf(bytes.size - 1))
                assertSafeFailure { withDownloadedBackup(client, config, item(), directory) { error("不得暂存") } }
                chunked.set(1)
                assertSafeFailure { withDownloadedBackup(client, config, item(), directory) { error("不得暂存") } }
                metadata.set(asset(digest = "sha256:bad"))
                assertSafeFailure { withDownloadedBackup(client, config, item(digest = null), directory) { error("不得暂存") } }
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        }
    }

    @Test
    fun `无摘要旧附件仍强制大小校验并可恢复`() = runBlocking {
        LocalServer { exchange ->
            standardDownload(exchange, asset(digest = null)) { exchange.respond(200, bytes) }
        }.use { server ->
            withDirectory { directory ->
                withDownloadedBackup(server.client(), config, item(digest = null), directory) {
                    assertArrayEquals(bytes, it.readBytes())
                }
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        }
    }

    @Test
    fun `下载前重新核对元数据且拒绝变更附件`() = runBlocking {
        val downloaded = AtomicInteger()
        LocalServer { exchange ->
            standardDownload(exchange, asset(name = "$tag-changed.zip")) {
                downloaded.incrementAndGet()
                exchange.respond(200, bytes)
            }
        }.use { server ->
            withDirectory { directory ->
                assertSafeFailure { withDownloadedBackup(server.client(), config, item(), directory) {} }
                assertEquals(0, downloaded.get())
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        }
    }

    @Test
    fun `响应声明超过2GiB立即拒绝且不读取巨大正文`() = runBlocking {
        LocalServer { exchange ->
            standardDownload(exchange) {
                exchange.sendResponseHeaders(200, GitHubReleaseClient.MAX_ARCHIVE_BYTES + 1)
                // 不分配巨大数组；客户端应在读正文前拒绝 Content-Length。
                runCatching { exchange.responseBody.close() }
                exchange.close()
            }
        }.use { server ->
            withDirectory { directory ->
                assertSafeFailure { withDownloadedBackup(server.client(), config, item(), directory) {} }
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        }
    }

    @Test
    fun `2GiB限制及磁盘预检无需申请大文件即可验证`() {
        listOf(-1L, 0L, GitHubReleaseClient.MAX_ARCHIVE_BYTES + 1, Long.MAX_VALUE).forEach {
            assertTrue(runCatching { validateDownloadSize(it) }.exceptionOrNull() is GitHubBackupException)
        }
        validateDownloadSize(1)
        validateDownloadSize(GitHubReleaseClient.MAX_ARCHIVE_BYTES)
        assertTrue(runCatching { checkDiskSpace(File("/does-not-exist-github-backup"), 1) }.exceptionOrNull() is GitHubBackupException)
    }

    @Test
    fun `取消响应头等待会取消HTTP Call并删除临时文件`() = runBlocking {
        cancellationScenario(streaming = false)
    }

    @Test
    fun `取消响应体流式读取会取消HTTP Call并删除临时文件`() = runBlocking {
        cancellationScenario(streaming = true)
    }

    private suspend fun cancellationScenario(streaming: Boolean) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = okhttp3.Dispatcher()
        LocalServer { exchange ->
            standardDownload(exchange) {
                if (streaming) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(bytes, 0, 1)
                    exchange.responseBody.flush()
                }
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                exchange.close()
            }
        }.use { server ->
            try {
                withDirectory { directory ->
                    val base = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dispatcher(dispatcher).build()
                    val client = GitHubReleaseClient.forLocalTests(server.url.toHttpUrl(), baseClient = base)
                    kotlinx.coroutines.coroutineScope {
                        val pending = async { withDownloadedBackup(client, config, item(), directory) { error("不得暂存") } }
                        // 此处必须让出 runBlocking 线程，让 async 启动后再等待服务器。
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            assertTrue(started.await(5, TimeUnit.SECONDS))
                        }
                        val call = dispatcher.runningCalls().single()
                        withTimeout(5000) { pending.cancelAndJoin() }
                        assertTrue(call.isCanceled())
                        assertTrue(pending.isCancelled)
                        assertTrue(directory.listFiles()!!.isEmpty())
                    }
                }
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `恢复消费失败仍清理下载文件`() = runBlocking {
        LocalServer { exchange ->
            standardDownload(exchange) { exchange.respond(200, bytes) }
        }.use { server ->
            withDirectory { directory ->
                val error = runCatching {
                    withDownloadedBackup(server.client(), config, item(), directory) {
                        throw IllegalStateException("模拟暂存失败")
                    }
                }.exceptionOrNull()
                assertTrue(error is IllegalStateException)
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        }
    }

    @Test
    fun `满百条无Link也读取下一页历史`() = runBlocking {
        val pages = CopyOnWriteArrayList<String>()
        LocalServer { exchange ->
            when (exchange.requestURI.path) {
                "/repos/owner/private-repo" -> exchange.respond(200, """{"private":true}""")
                "/repos/owner/private-repo/releases" -> {
                    pages += exchange.requestURI.rawQuery
                    if (exchange.requestURI.rawQuery.split('&').contains("page=1")) {
                        exchange.respond(200, (1L..100L).joinToString(",", "[", "]") { release(it, "unrelated-$it") })
                    } else exchange.respond(200, "[${release(101, tag)}]")
                }
                else -> exchange.respond(200, "[${asset()}]")
            }
        }.use { server ->
            assertEquals(1, server.client().list(config).size)
            assertEquals(listOf("per_page=100&page=1", "per_page=100&page=2"), pages)
        }
    }

    @Test
    fun `无效配置和超限下载在网络访问前失败`() = runBlocking {
        val requests = AtomicInteger()
        LocalServer {
            requests.incrementAndGet()
            it.respond(500, "不得产生网络请求")
        }.use { server ->
            assertSafeFailure { server.client().test(GitHubBackupConfig("../owner", "repo", config.token)) }
            withDirectory { directory ->
                assertSafeFailure {
                    withDownloadedBackup(
                        server.client(), config, item().copy(size = GitHubReleaseClient.MAX_ARCHIVE_BYTES + 1), directory,
                    ) {}
                }
                assertTrue(directory.listFiles()!!.isEmpty())
            }
            assertEquals(0, requests.get())
        }
    }

    @Test
    fun `HTTP错误和畸形响应均不回显敏感内容`() = runBlocking {
        val status = AtomicInteger(401)
        LocalServer { it.respond(status.get(), "private-response-body ${config.token}") }.use { server ->
            listOf(401, 403, 404, 422, 429, 500, 200).forEach {
                status.set(it)
                assertSafeFailure { server.client().test(config) }
            }
        }
        listOf(IOExceptionWithSecret(), IllegalArgumentException(config.token)).forEach {
            val error = safeGitHubError(it)
            assertFalse(error.toString().contains(config.token))
            assertNull(error.cause)
        }
    }

    @Test
    fun `元数据正文限制阻止无界JSON读取`() = runBlocking {
        LocalServer { exchange ->
            exchange.sendResponseHeaders(200, 0)
            runCatching { exchange.responseBody.use { it.write(ByteArray(8 * 1024 * 1024 + 1) { 32 }) } }
            exchange.close()
        }.use { server -> assertSafeFailure { server.client().test(config) } }
    }

    private fun item(digest: `String`? = sha256(bytes)) = GitHubBackupItem(1, "$tag.zip", bytes.size.toLong(), "2026-11-21T12:34:56Z", tag, tag, digest)

    private fun asset(id: Long = 1, name: String = "$tag.zip", digest: `String`? = sha256(bytes)): String = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "size" to JsonPrimitive(bytes.size),
            "created_at" to JsonPrimitive("2026-11-21T12:34:56Z"), "digest" to JsonPrimitive(digest),
        ),
    ).toString()

    private fun release(id: Long, value: String, draft: Boolean = false): String =
        """{"id":$id,"tag_name":"$value","name":"$value","draft":$draft}"""

    private fun standardDownload(exchange: HttpExchange, metadata: String = asset(), download: () -> Unit) {
        when {
            exchange.requestURI.path == "/repos/owner/private-repo" -> exchange.respond(200, """{"private":true}""")
            exchange.requestHeaders.getFirst("Accept") == "application/vnd.github+json" -> exchange.respond(200, metadata)
            else -> download()
        }
    }

    private suspend fun assertSafeFailure(vararg messages: String, block: suspend () -> Any?) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("应返回脱敏备份错误，实际为 $error", error is GitHubBackupException)
        assertNull(error?.cause)
        messages.forEach { assertTrue("错误应包含 $it，实际为 $error", error?.message.orEmpty().contains(it)) }
        generateSequence(error) { it.cause }.take(8).forEach {
            assertFalse(it.toString().contains(config.token))
            assertFalse(it.toString().contains("private-response-body"))
            assertFalse(it.toString().contains("127.0.0.1"))
        }
    }

    private suspend fun <T> withDirectory(block: suspend (File) -> T): T {
        val directory = Files.createTempDirectory("github-backup-test-").toFile()
        try {
            return block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sha256(value: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }

    private class IOExceptionWithSecret : java.io.IOException("test-secret-pat private-response-body")

    private data class RequestSnapshot(
        val path: String,
        val query: `String`?,
        val method: String,
        val authorization: `String`?,
        val accept: `String`?,
        val version: `String`?,
        val contentType: `String`?,
    )

    private fun HttpExchange.snapshot() = RequestSnapshot(
        requestURI.path, requestURI.rawQuery, requestMethod,
        requestHeaders.getFirst("Authorization"), requestHeaders.getFirst("Accept"),
        requestHeaders.getFirst("X-GitHub-Api-Version"), requestHeaders.getFirst("Content-Type"),
    )

    private class LocalServer(handler: (HttpExchange) -> Unit) : Closeable {
        private val executor = Executors.newCachedThreadPool()
        private val failure = AtomicReference<Throwable>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@LocalServer.executor
            createContext("/") { exchange ->
                try {
                    handler(exchange)
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    runCatching { exchange.sendResponseHeaders(500, -1) }
                    exchange.close()
                }
            }
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}/"

        fun client(): GitHubReleaseClient = GitHubReleaseClient.forLocalTests(
            url.toHttpUrl(), now = { LocalDateTime.of(2026, 11, 21, 12, 34, 56) },
        )

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            failure.get()?.let { throw AssertionError("本地测试服务处理请求失败", it) }
        }
    }
}

private fun HttpExchange.respond(status: Int, body: String) = respond(status, body.toByteArray())

private fun HttpExchange.respond(status: Int, body: ByteArray) {
    use {
        if (body.isEmpty()) sendResponseHeaders(status, -1) else {
            sendResponseHeaders(status, body.size.toLong())
            responseBody.write(body)
        }
    }
}
