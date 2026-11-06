package dev.ide.core.agent

import dev.ide.agent.impl.LlmTransport
import dev.ide.agent.impl.SseRequest
import dev.ide.ui.backend.UiOpenRouterSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The browser half of "Sign in with OpenRouter", run for real over loopback: OpenRouter's redirect lands on the
 * listener, the code is exchanged, and a free account is moved onto a free model.
 */
class OpenRouterSignInTest {

    private val transport = object : LlmTransport {
        override fun sse(request: SseRequest): Flow<String> = emptyFlow()
        override suspend fun post(url: String, headers: Map<String, String>, jsonBody: String, caCertificatePem: String?) =
            if ("\"code\":\"good\"" in jsonBody) """{"key":"sk-or-test"}""" else throw dev.ide.agent.impl.LlmHttpException("Invalid code", 403)
        override suspend fun get(url: String, headers: Map<String, String>, caCertificatePem: String?) = when {
            url.endsWith("/key") -> """{"data":{"is_free_tier":true}}"""
            else -> """{"data":[{"id":"qwen/qwen3-coder:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"]}]}"""
        }
    }

    private fun callbackOf(authUrl: String): String {
        val q = OpenRouterSignIn.parseQuery(URI(authUrl).rawQuery)
        return q.getValue("callback_url").replace("localhost", "127.0.0.1")
    }

    private fun stateOf(authUrl: String) = OpenRouterSignIn.parseQuery(URI(authUrl).rawQuery).getValue("state")

    private fun hit(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return conn.inputStream.use { it.readBytes().decodeToString() }
    }

    @Test
    fun redirectSavesTheKeyAndPicksAFreeModel() {
        runBlocking {
            var saved: Pair<String, String?>? = null
            val signIn = OpenRouterSignIn(transport, CoroutineScope(SupervisorJob() + Dispatchers.Default)) { k, m -> saved = k to m }
            val url = assertNotNull(signIn.begin())
            assertEquals(UiOpenRouterSignIn.Waiting, signIn.state.value)

            val page = hit("${callbackOf(url)}?code=good&state=${stateOf(url)}")
            assertTrue("OpenRouter connected" in page)

            val done = withTimeout(5_000) { signIn.state.first { it !is UiOpenRouterSignIn.Waiting } }
            assertEquals(UiOpenRouterSignIn.Connected("qwen/qwen3-coder:free"), done)
            assertEquals("sk-or-test" to "qwen/qwen3-coder:free", saved)
        }
    }

    @Test
    fun aRedirectFromAnotherSignInIsRejected() {
        runBlocking {
            var saved = false
            val signIn = OpenRouterSignIn(transport, CoroutineScope(SupervisorJob() + Dispatchers.Default)) { _, _ -> saved = true }
            val url = assertNotNull(signIn.begin())
            hit("${callbackOf(url)}?code=good&state=forged")
            val done = withTimeout(5_000) { signIn.state.first { it !is UiOpenRouterSignIn.Waiting } }
            assertIs<UiOpenRouterSignIn.Failed>(done)
            assertTrue(!saved)
        }
    }

    @Test
    fun aRefusedCodeReportsFailure() {
        runBlocking {
            val signIn = OpenRouterSignIn(transport, CoroutineScope(SupervisorJob() + Dispatchers.Default)) { _, _ -> }
            val url = assertNotNull(signIn.begin())
            hit("${callbackOf(url)}?code=bad&state=${stateOf(url)}")
            val done = withTimeout(5_000) { signIn.state.first { it !is UiOpenRouterSignIn.Waiting } }
            assertIs<UiOpenRouterSignIn.Failed>(done)
        }
    }
}
