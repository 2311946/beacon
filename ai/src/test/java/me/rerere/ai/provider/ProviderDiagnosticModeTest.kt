package me.rerere.ai.provider

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.claude.generateClaudeWithPauseTurn
import me.rerere.ai.provider.providers.configureAccountAuth
import me.rerere.ai.provider.providers.grok.GrokProvider
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.withAccountAuthenticator
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ProviderDiagnosticModeTest {
    @Test
    fun `local credentials read latest settings on every request`() = runBlocking {
        val stale = ProviderSetting.ChatGPT(credential = OAuthCredential("stale", expiresAt = 10_000))
        var latest: List<ProviderSetting> = listOf(stale.copy(credential = OAuthCredential("first", expiresAt = 2_000)))
        val credentials = LocalAccountCredentialProvider(providers = { latest }, now = { 1_000 })

        assertEquals("first", credentials.getCredential(stale, null).accessToken)
        latest = listOf(stale.copy(credential = OAuthCredential("second", expiresAt = 3_000)))
        assertEquals("second", credentials.getCredential(stale, null).accessToken)
        assertEquals("stale", stale.credential?.accessToken)
    }

    @Test
    fun `missing expired unknown and blank local credentials fail without stale fallback`() = runBlocking {
        val stale = ProviderSetting.ChatGPT(credential = OAuthCredential("stale", expiresAt = 10_000))
        val invalidSettings = listOf(
            emptyList(),
            listOf(stale.copy(credential = null)),
            listOf(stale.copy(enabled = false)),
            listOf(stale, stale.copy()),
            listOf(stale.copy(credential = OAuthCredential("expired", refreshToken = "refresh", expiresAt = 999))),
            listOf(stale.copy(credential = OAuthCredential("boundary", expiresAt = 1_000))),
            listOf(stale.copy(credential = OAuthCredential("unknown"))),
            listOf(stale.copy(credential = OAuthCredential(" ", expiresAt = 2_000))),
            listOf(ProviderSetting.Grok(id = stale.id, credential = stale.credential)),
        )
        invalidSettings.forEach { latest ->
            val credentials = LocalAccountCredentialProvider(providers = { latest }, now = { 1_000 })
            val failure = runCatching { credentials.getCredential(stale, null) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("No valid local credential for diagnostics", failure?.message)
        }
    }

    @Test
    fun `local credentials reject authentication retries before reading settings`() = runBlocking {
        val credentials = LocalAccountCredentialProvider(providers = { error("must not read") })
        val failure = runCatching {
            credentials.getCredential(ProviderSetting.Grok(), "rejected-secret")
        }.exceptionOrNull()
        assertEquals("Diagnostic authentication retry is disabled", failure?.message)
    }

    @Test
    fun `normal account authentication still refreshes rejected token by default`() = runBlocking {
        val setting = ProviderSetting.ChatGPT()
        val rejectedTokens = mutableListOf<String?>()
        val credentials = AccountCredentialProvider { _, rejected ->
            rejectedTokens += rejected
            OAuthCredential(if (rejected == null) "initial" else "refreshed")
        }
        val client = OkHttpClient().withAccountAuthenticator(credentials)
        val request = Request.Builder().url("https://example.test/responses").apply {
            configureAccountAuth(credentials, setting, emptyList()) {}
        }.build()
        val response = response(request, 401, "")

        val retry = client.authenticator.authenticate(null, response)

        assertNotNull(retry)
        assertEquals("Bearer refreshed", retry?.header("Authorization"))
        assertEquals(listOf(null, "initial"), rejectedTokens)
        assertNull(client.authenticator.authenticate(
            null, response.newBuilder().priorResponse(response.newBuilder().body(null).build()).build(),
        ))
    }

    @Test
    fun `diagnostic account providers do not reinstall authenticators`() {
        val credentials = AccountCredentialProvider { _, _ -> error("must not refresh") }
        val inheritedClient = OkHttpClient.Builder().authenticator { _, response -> response.request }.build()
        val providers = listOf(
            ChatGPTProvider(inheritedClient, credentials, diagnosticMode = true),
            GrokProvider(inheritedClient, credentials, diagnosticMode = true),
        )
        providers.forEach { provider ->
            val field = provider.javaClass.getDeclaredField("client").apply { isAccessible = true }
            val client = field.get(provider) as OkHttpClient
            assertSame(Authenticator.NONE, client.authenticator)
        }
    }

    @Test
    fun `normal Claude pause turn still allows five continuations by default`() = runBlocking {
        var requests = 0
        val result = generateClaudeWithPauseTurn(listOf(UIMessage.user("ping")), Model()) {
            requests++
            TextGenerationResult(
                id = "msg_$requests",
                model = "claude-test",
                message = UIMessage.assistant("paused"),
                finishReason = "pause_turn",
                usage = null,
            )
        }

        assertEquals(6, requests)
        assertEquals("pause_turn", result.finishReason)
    }

    @Test
    fun `diagnostic Claude non streaming pause turn sends one request`() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            response(chain.request(), 200, """{"id":"msg_1","model":"claude-test","content":[],"stop_reason":"pause_turn"}""")
        }.build()
        val result = ClaudeProvider(client, diagnosticMode = true).generateText(
            ProviderSetting.Claude(), listOf(UIMessage.user("ping")), TextGenerationParams(Model(modelId = "claude-test")),
        )

        assertEquals(1, requests)
        assertEquals("pause_turn", result.finishReason)
    }

    @Test
    fun `diagnostic Claude streaming pause turn sends one request`() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            response(chain.request(), 200, "event: message_delta\ndata: {\"delta\":{\"stop_reason\":\"pause_turn\"}}\n\nevent: message_stop\ndata: {}\n\n")
        }.build()
        val chunks = withTimeout(5_000) {
            ClaudeProvider(client, diagnosticMode = true).streamText(
                ProviderSetting.Claude(), listOf(UIMessage.user("ping")), TextGenerationParams(Model(modelId = "claude-test")),
            ).toList()
        }

        assertEquals(1, requests)
        assertEquals("pause_turn", chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }

    @Test
    fun `diagnostic Responses parser does not print raw response`() {
        val original = System.out
        val output = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(output))
            val api = ResponseAPI(OkHttpClient(), diagnosticMode = true)
            val result = api.parseResponseOutput(json.parseToJsonElement(
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"private-response"}]}]}"""
            ).jsonObject)
            assertEquals("private-response", (result.message.parts.single() as UIMessagePart.Text).text)
            assertEquals("", output.toString())
        } finally {
            System.setOut(original)
        }
    }

    @Test
    fun `diagnostic ChatGPT failure neither refreshes nor prints raw error`() = runBlocking {
        val originalOut = System.out
        val originalErr = System.err
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        var requests = 0
        var credentialReads = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            response(chain.request(), 401, """{"error":{"message":"private-server-error"}}""")
        }.build()
        val credentials = AccountCredentialProvider { _, rejected ->
            assertNull(rejected)
            credentialReads++
            OAuthCredential("local-token", expiresAt = Long.MAX_VALUE)
        }
        try {
            System.setOut(PrintStream(output))
            System.setErr(PrintStream(errors))
            val failure = runCatching {
                withTimeout(5_000) {
                    ChatGPTProvider(client, credentials, diagnosticMode = true).generateText(
                        ProviderSetting.ChatGPT(), listOf(UIMessage.user("ping")), TextGenerationParams(Model()),
                    )
                }
            }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(1, requests)
            assertEquals(1, credentialReads)
            assertEquals("", output.toString())
            assertEquals("", errors.toString())
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    private fun response(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body(body.toResponseBody(null))
        .build()
}
