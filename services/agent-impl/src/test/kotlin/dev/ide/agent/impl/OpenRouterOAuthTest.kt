package dev.ide.agent.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Sign in with OpenRouter": the PKCE pieces, the code exchange, and which free model a new account starts on. */
class OpenRouterOAuthTest {

    @Test
    fun challengeIsUnpaddedBase64UrlOfSha256() {
        assertEquals(
            // Cross-checked with Python: base64.urlsafe_b64encode(hashlib.sha256(v).digest()).rstrip("=")
            "GQ2lpIc1YS_6ROBiKySWzwT8UnooSiIw15amly3fLT8",
            OpenRouterOAuth.challenge("dBjftJeZ4CVP-mJ92K9TrbJ0YbEZJNRVpwZPKIvPmFE"),
        )
    }

    @Test
    fun authUrlCarriesCallbackChallengeAndState() {
        val attempt = OpenRouterOAuth.start("http://localhost:51423/openrouter-callback")
        val uri = URI(attempt.url)
        assertEquals("openrouter.ai", uri.host)
        assertEquals("/auth", uri.path)
        val q = uri.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("http://localhost:51423/openrouter-callback", q["callback_url"])
        assertEquals(OpenRouterOAuth.challenge(attempt.verifier), q["code_challenge"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(attempt.state, q["state"])
        assertTrue(attempt.verifier.length >= 43, "RFC 7636 needs a 43+ character verifier")
    }

    @Test
    fun exchangeSendsCodeAndVerifierAndReturnsTheKey() {
        runBlocking {
            val transport = Fake(postBody = """{"key":"sk-or-v1-abc","user_id":"u"}""")
            assertEquals("sk-or-v1-abc", OpenRouterOAuth.exchange(transport, "the-code", "the-verifier"))
            assertEquals(OpenRouterOAuth.KEYS_URL, transport.postUrl)
            val body = AgentJson.parseToJsonElement(transport.postJson!!).asObj()!!
            assertEquals("the-code", body["code"].asStr())
            assertEquals("the-verifier", body["code_verifier"].asStr())
            assertEquals("S256", body["code_challenge_method"].asStr())
        }
    }

    @Test
    fun exchangeWithoutAKeyFails() {
        assertFailsWith<LlmHttpException> {
            runBlocking { OpenRouterOAuth.exchange(Fake(postBody = """{"error":"nope"}"""), "c", "v") }
        }
    }

    @Test
    fun freeModelMustTakeToolsAndPrefersCoders() {
        val models = """{"data":[
            {"id":"anthropic/claude-sonnet-5","pricing":{"prompt":"0.000003","completion":"0.000015"},"supported_parameters":["tools"],"context_length":200000},
            {"id":"big/chat:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["temperature"],"context_length":1000000},
            {"id":"meta/llama:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"],"context_length":131072},
            {"id":"qwen/qwen3-coder:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools","tool_choice"],"context_length":65536}
        ]}"""
        assertEquals("qwen/qwen3-coder:free", OpenRouterOAuth.pickFreeAgentModel(models))
    }

    @Test
    fun noFreeToolModelMeansNoPick() {
        val models = """{"data":[{"id":"x/y:free","supported_parameters":[]},{"id":"a/b","pricing":{"prompt":"0.1","completion":"0.1"},"supported_parameters":["tools"]}]}"""
        assertNull(OpenRouterOAuth.pickFreeAgentModel(models))
    }

    @Test
    fun freeTierIsReadFromTheKeyEndpoint() {
        runBlocking {
            assertEquals(true, OpenRouterOAuth.isFreeTier(Fake(getBody = """{"data":{"is_free_tier":true}}"""), "k"))
            assertEquals(false, OpenRouterOAuth.isFreeTier(Fake(getBody = """{"data":{"is_free_tier":false}}"""), "k"))
            assertNull(OpenRouterOAuth.isFreeTier(Fake(getBody = "not json"), "k"))
        }
    }

    private class Fake(val postBody: String = "{}", val getBody: String = "{}") : LlmTransport {
        var postUrl: String? = null
        var postJson: String? = null
        override fun sse(request: SseRequest): Flow<String> = emptyFlow()
        override suspend fun get(url: String, headers: Map<String, String>, caCertificatePem: String?): String = getBody
        override suspend fun post(url: String, headers: Map<String, String>, jsonBody: String, caCertificatePem: String?): String {
            postUrl = url
            postJson = jsonBody
            return postBody
        }
    }
}
