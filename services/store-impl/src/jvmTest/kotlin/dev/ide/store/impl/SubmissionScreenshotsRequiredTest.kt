package dev.ide.store.impl

import dev.ide.store.PackagedProject
import dev.ide.store.StoreResult
import dev.ide.store.StoreSubmissionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A listing needs at least one screenshot, and a first submission without one is refused before anything is
 * uploaded or any sign-in is consulted.
 */
class SubmissionScreenshotsRequiredTest {

    private val service = SupabaseSubmissionService(
        "http://127.0.0.1:1",
        "anon",
        SupabaseAccountService("http://127.0.0.1:1", "anon", "codeassist://auth-callback"),
    )
    private val packaged = PackagedProject(emptyList(), emptyList(), 0, "", "/nonexistent.zip")

    private fun request(itemSlug: String? = null, shots: List<String> = emptyList()) = StoreSubmissionRequest(
        itemSlug = itemSlug,
        title = "My App",
        summary = "Does one thing",
        description = "The long version",
        category = "utilities",
        screenshotPaths = shots,
    )

    @Test
    fun aNewListingWithoutScreenshotsIsRefused() {
        val result = service.submit(request(), packaged)
        assertEquals(SupabaseSubmissionService.NO_SCREENSHOTS, (result as? StoreResult.Failed)?.message)
    }

    /** An update that sends none keeps the listing's gallery, so the client cannot refuse it on its own. */
    @Test
    fun anUpdateWithoutScreenshotsIsNotRefusedForThat() {
        val result = service.submit(request(itemSlug = "my-app-ab12"), packaged)
        assertNotEquals(SupabaseSubmissionService.NO_SCREENSHOTS, (result as? StoreResult.Failed)?.message)
    }

    @Test
    fun aNewListingWithAScreenshotGetsPastTheCheck() {
        val result = service.submit(request(shots = listOf("/tmp/shot.png")), packaged)
        assertNotEquals(SupabaseSubmissionService.NO_SCREENSHOTS, (result as? StoreResult.Failed)?.message)
    }
}
