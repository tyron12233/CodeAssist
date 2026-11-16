package dev.ide.agent.ui

import dev.ide.ui.backend.UiAgentModel
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which of a gateway's fetched models are listed under the model field, given what is typed in it. */
class GatewayModelMatchesTest {

    private val models = listOf("llama3.1:8b", "qwen2.5-coder:7b", "qwen2.5-coder:32b", "deepseek-r1").map { UiAgentModel(it, it) }

    private fun ids(typed: String) = gatewayModelMatches(models, typed).map { it.id }

    @Test
    fun anEmptyFieldListsEverything() {
        assertEquals(models.map { it.id }, ids("  "))
    }

    @Test
    fun typingNarrowsTheListWhateverTheCase() {
        assertEquals(listOf("qwen2.5-coder:7b", "qwen2.5-coder:32b"), ids("QWEN"))
    }

    @Test
    fun aNameThatIsAlreadyOneOfThemKeepsTheWholeList() {
        assertEquals(models.map { it.id }, ids("deepseek-r1"))
    }

    @Test
    fun aNameTheEndpointDoesNotServeStillShowsWhatItDoes() {
        assertEquals(models.map { it.id }, ids("gpt-4o"))
    }
}
