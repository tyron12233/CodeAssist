package dev.ide.agent.ui

import dev.ide.ui.backend.UiAgentModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The model picker's search, filters, sort and sections, over an OpenRouter-shaped list. */
class ModelPickerFilterTest {

    private val models = listOf(
        UiAgentModel("anthropic/claude-sonnet-5", "Anthropic: Claude Sonnet 5"),
        UiAgentModel("anthropic/claude-opus-5", "Anthropic: Claude Opus 5"),
        UiAgentModel("openai/gpt-5", "OpenAI: GPT-5"),
        UiAgentModel("qwen/qwen3-coder:free", "Qwen: Qwen3 Coder (free)"),
        UiAgentModel("meta-llama/llama-4-maverick:free", "Meta: Llama 4 Maverick (free)"),
        UiAgentModel("deepseek/deepseek-chat", "DeepSeek: DeepSeek V3.2"),
    )

    private fun ids(list: List<UiAgentModel>) = list.map { it.id }

    @Test
    fun vendorNameInTheQueryNarrowsToThatVendor() {
        val s = sectionModels(models, ModelFilter(query = "anthropic"), emptySet(), emptyList())
        assertEquals(listOf("anthropic/claude-opus-5", "anthropic/claude-sonnet-5"), ids(s.all))
    }

    @Test
    fun everyTermMustMatch() {
        val s = sectionModels(models, ModelFilter(query = "  Anthropic   SONNET "), emptySet(), emptyList())
        assertEquals(listOf("anthropic/claude-sonnet-5"), ids(s.all))
    }

    @Test
    fun freeOnlyKeepsTheFreeVariants() {
        val s = sectionModels(models, ModelFilter(freeOnly = true), emptySet(), emptyList())
        assertTrue(s.all.all { it.id.endsWith(":free") })
        assertEquals(2, s.all.size)
    }

    @Test
    fun vendorFilterKeepsOneVendor() {
        val s = sectionModels(models, ModelFilter(vendor = "openai"), emptySet(), emptyList())
        assertEquals(listOf("openai/gpt-5"), ids(s.all))
    }

    @Test
    fun nameSortIgnoresTheVendorPrefix() {
        val s = sectionModels(models, ModelFilter(sort = ModelSort.NAME), emptySet(), emptyList())
        // "Claude Opus", "Claude Sonnet", "DeepSeek", "GPT-5", "Llama", "Qwen3"; not grouped by "Anthropic:"/"Meta:".
        assertEquals(
            listOf("anthropic/claude-opus-5", "anthropic/claude-sonnet-5", "deepseek/deepseek-chat", "openai/gpt-5", "meta-llama/llama-4-maverick:free", "qwen/qwen3-coder:free"),
            ids(s.all),
        )
    }

    @Test
    fun vendorSortGroupsByVendor() {
        val s = sectionModels(models, ModelFilter(sort = ModelSort.VENDOR), emptySet(), emptyList())
        assertEquals(listOf("anthropic", "anthropic", "deepseek", "meta-llama", "openai", "qwen"), s.all.map { modelVendor(it.id) })
    }

    @Test
    fun favoritesThenRecentsThenTheRestWithNoRepeats() {
        val s = sectionModels(
            models, ModelFilter(),
            favorites = setOf("openai/gpt-5"),
            recents = listOf("qwen/qwen3-coder:free", "openai/gpt-5", "gone/model"),
        )
        assertEquals(listOf("openai/gpt-5"), ids(s.favorites))
        assertEquals(listOf("qwen/qwen3-coder:free"), ids(s.recents), "a favorite is not repeated, a vanished model is dropped")
        assertEquals(models.size - 2, s.all.size)
    }

    @Test
    fun filtersApplyToFavoritesAndRecentsToo() {
        val s = sectionModels(models, ModelFilter(freeOnly = true), favorites = setOf("openai/gpt-5"), recents = listOf("anthropic/claude-opus-5"))
        assertTrue(s.favorites.isEmpty() && s.recents.isEmpty())
    }

    @Test
    fun bareIdsHaveNoVendorAndKeepTheirName() {
        assertNull(modelVendor("claude-opus-5"))
        assertEquals("Claude Opus 5", modelName(UiAgentModel("claude-opus-5", "Claude Opus 5")))
        assertEquals("GPT-5", modelName(UiAgentModel("openai/gpt-5", "OpenAI: GPT-5")))
    }

    @Test
    fun numbersSortAsNumbers() {
        val names = listOf("Model 10", "Model 2", "GPT-5", "GPT-4.1", "gpt-4o", "Model 1")
        assertEquals(listOf("GPT-4.1", "gpt-4o", "GPT-5", "Model 1", "Model 2", "Model 10"), names.sortedWith(::naturalCompare))
    }
}
