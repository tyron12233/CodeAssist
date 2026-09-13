package dev.ide.store.impl

import dev.ide.store.StoreSubmissionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `listing_patch` a version carries, as JSON.
 *
 * This is a wire contract with a CHECK constraint: the column accepts only `title`, `summary`,
 * `description`, `category` and `tags`, so a sixth key here is not a harmless extra field, it is an insert
 * the database refuses after the archive has already been uploaded.
 */
class SubmissionListingPatchTest {

    private fun request(
        itemSlug: String? = "my-app-ab12",
        editsListing: Boolean = true,
        title: String = "My App",
        summary: String = "Does one thing",
        description: String = "The long version",
        category: String = "utilities",
        tags: List<String> = listOf("kotlin"),
    ) = StoreSubmissionRequest(
        itemSlug = itemSlug,
        title = title,
        summary = summary,
        description = description,
        category = category,
        tags = tags,
        editsListing = editsListing,
    )

    @Test
    fun anUpdateThatEditsItsListingSendsTheFieldsTheColumnAccepts() {
        val json = SupabaseSubmissionService.listingPatchJson(request())

        assertEquals(
            """{"title":"My App","summary":"Does one thing","description":"The long version",""" +
                """"category":"utilities","tags":["kotlin"]}""",
            json,
        )
    }

    /** A first submission writes the item row itself; a patch as well would describe it twice. */
    @Test
    fun aNewListingCarriesNoPatch() {
        assertNull(SupabaseSubmissionService.listingPatchJson(request(itemSlug = null)))
    }

    /** A form that never loaded the listing has no opinion about it, whatever is in its fields. */
    @Test
    fun anUpdateThatProposesNoEditCarriesNoPatch() {
        assertNull(SupabaseSubmissionService.listingPatchJson(request(editsListing = false)))
    }

    /**
     * A blank field is left out rather than sent empty.
     *
     * The backend reads a blank as "unchanged" too, so this is not what keeps a summary from being erased.
     * It is that a submission should not claim to propose a title it does not have.
     */
    @Test
    fun blankFieldsAreLeftOutOfThePatch() {
        val json = assertNotNullPatch(request(title = "  ", description = ""))

        assertTrue("title" !in json, json)
        assertTrue("description" !in json, json)
        assertTrue("""summary":"Does one thing""" in json, json)
    }

    /**
     * The tags key is always present, empty included.
     *
     * Removing the last tag is an edit, and an absent key is what the backend reads as "leave the tags
     * alone", so the two cases have to look different on the wire.
     */
    @Test
    fun removingEveryTagStillSaysSo() {
        assertTrue("""tags":[]""" in assertNotNullPatch(request(tags = emptyList())))
    }

    /** `cardinality(tags) <= 10` is a CHECK on the item, so an eleventh tag would fail the insert. */
    @Test
    fun tagsAreTrimmedDeduplicatedByTheCallerAndCappedHere() {
        val json = assertNotNullPatch(request(tags = (1..14).map { " tag$it " }))

        assertTrue(""""tag10"""" in json, json)
        assertTrue(""""tag11"""" !in json, json)
        assertTrue(""" tag1 """ !in json, "tags should reach the column trimmed")
    }

    /** The text is a publisher's, so it goes through the same escaping every other field does. */
    @Test
    fun textIsEscapedRatherThanPastedIntoTheJson() {
        val json = assertNotNullPatch(request(summary = """a "quoted" line\and a slash"""))

        assertTrue("""\"quoted\"""" in json, json)
        assertTrue("""\\and""" in json, json)
    }

    private fun assertNotNullPatch(request: StoreSubmissionRequest): String =
        requireNotNull(SupabaseSubmissionService.listingPatchJson(request)) { "expected a patch" }
}
