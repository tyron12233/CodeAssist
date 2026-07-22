package dev.ide.agent.impl

import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmProviderRegistry
import dev.ide.agent.SimpleLlmProviderRegistry

/** Assembles the built-in providers over a transport (OkHttp by default). Plugins may add more providers. */
object AgentProviders {
    fun defaults(transport: LlmTransport = OkHttpLlmTransport()): List<LlmProvider> =
        listOf(
            AnthropicProvider(transport),
            OpenAiProvider(transport),
            GeminiProvider(transport),
        )

    fun registry(transport: LlmTransport = OkHttpLlmTransport()): LlmProviderRegistry =
        SimpleLlmProviderRegistry(defaults(transport))
}
