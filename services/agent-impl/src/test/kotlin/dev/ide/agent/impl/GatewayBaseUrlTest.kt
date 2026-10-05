package dev.ide.agent.impl

import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A custom gateway's base URL is whatever the user typed or pasted: the OpenAI SDK form ending in `/v1`, a full
 * endpoint, or a copied "URL + key" block. Each must still reach `<root>/v1/...`, and a local server with no key
 * must not be sent an empty bearer.
 */
class GatewayBaseUrlTest {

    private class Recording : LlmTransport {
        var url: String? = null
        var headers: Map<String, String> = emptyMap()
        var getUrl: String? = null
        var getHeaders: Map<String, String> = emptyMap()
        var modelsBody = """{"data":[{"id":"phi3:mini"},{"id":"llama3.2"}]}"""

        override fun sse(request: SseRequest): Flow<String> {
            url = request.url
            headers = request.headers
            return flowOf("[DONE]")
        }

        override suspend fun get(url: String, headers: Map<String, String>, caCertificatePem: String?): String {
            getUrl = url
            getHeaders = headers
            return modelsBody
        }
    }

    private fun send(t: Recording, config: ProviderConfig) = runBlocking {
        OpenAiProvider(t).client(config).chat(LlmRequest("phi3:mini", null, listOf(LlmMessage.user("hi")))).toList()
    }

    @Test
    fun normalizesTheCommonShapesOfABaseUrl() {
        val root = "http://127.0.0.1:11434"
        assertEquals(root, OpenAiProvider.normalizeBase(root))
        assertEquals(root, OpenAiProvider.normalizeBase("$root/"))
        assertEquals(root, OpenAiProvider.normalizeBase(" $root/v1/ "))
        assertEquals(root, OpenAiProvider.normalizeBase("$root/v1/chat/completions"))
        assertEquals(root, OpenAiProvider.normalizeBase("$root/v1/models"))
        assertEquals("https://openrouter.ai/api", OpenAiProvider.normalizeBase("https://openrouter.ai/api/v1"))
        assertEquals("https://gigachat.example/api", OpenAiProvider.normalizeBase("https://gigachat.example/api/v1"))
        assertNull(OpenAiProvider.normalizeBase("   "))
        assertNull(OpenAiProvider.normalizeBase(null))
    }

    @Test
    fun aPastedBlockKeepsOnlyTheUrl() {
        val pasted = "http://127.0.0.1:11434/v1\nAPI Key: llmp_secret"
        assertEquals("http://127.0.0.1:11434", OpenAiProvider.normalizeBase(pasted))
        assertEquals("http://127.0.0.1:11434", OpenAiProvider.normalizeBase("Base URL: http://127.0.0.1:11434/v1 API Key: x"))

        val t = Recording()
        send(t, ProviderConfig("", baseUrl = pasted))
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", t.url)
    }

    @Test
    fun aKeylessGatewaySendsNoAuthorizationHeader() {
        val t = Recording()
        send(t, ProviderConfig("  ", baseUrl = "http://127.0.0.1:11434/v1"))
        assertFalse(t.headers.keys.any { it.equals("Authorization", ignoreCase = true) }, t.headers.toString())

        send(t, ProviderConfig("sk-1", baseUrl = "http://127.0.0.1:11434/v1"))
        assertEquals("Bearer sk-1", t.headers["Authorization"])
    }

    @Test
    fun aGatewayModelListIsNotFilteredToOpenAiModels() {
        val t = Recording()
        val models = runBlocking { OpenAiProvider(t).listModels(ProviderConfig("", baseUrl = "http://127.0.0.1:11434/v1")) }
        assertEquals("http://127.0.0.1:11434/v1/models", t.getUrl)
        assertEquals(listOf("llama3.2", "phi3:mini"), models.map { it.id })
        assertTrue(t.getHeaders.isEmpty(), t.getHeaders.toString())
    }

    @Test
    fun aPlainPageNotFoundPointsAtTheBaseUrlNotTheModel() {
        val url = "http://127.0.0.1:11434/v1/api/v1/chat/completions"
        val parsed = LlmErrors.parseHttp(404, "404 page not found", null, url)
        assertEquals(LlmErrorKind.ENDPOINT_NOT_FOUND, parsed.kind)
        assertTrue(parsed.message.contains(url) && parsed.message.contains("base URL"), parsed.message)

        // A web dashboard on the wrong port answers in its framework's own shapes; still the address, not the model.
        for (body in listOf("""{"error":"Not Found"}""", """{"detail":"Not Found"}""", """{"message":"Cannot POST"}""")) {
            val shaped = LlmErrors.parseHttp(404, body, null, "http://127.0.0.1:8080/v1/chat/completions")
            assertEquals(LlmErrorKind.ENDPOINT_NOT_FOUND, shaped.kind, body)
            assertTrue(shaped.message.contains(":8080/v1/chat/completions"), shaped.message)
        }

        // A string error that names the model is about the model, and its text is shown.
        val stringModel = LlmErrors.parseHttp(404, """{"error":"model 'qwen.gguf' not found"}""", null, url)
        assertEquals(LlmErrorKind.NOT_FOUND, stringModel.kind)
        assertTrue(stringModel.message.contains("model 'qwen.gguf' not found"), stringModel.message)

        // A provider's own not-found (a missing model) keeps the model wording.
        val model = LlmErrors.parseHttp(404, """{"error":{"type":"not_found_error","message":"model 'x' not found"}}""", null, url)
        assertTrue(model.message.contains("model"), model.message)
        assertFalse(model.message.contains("base URL"), model.message)
    }
}
