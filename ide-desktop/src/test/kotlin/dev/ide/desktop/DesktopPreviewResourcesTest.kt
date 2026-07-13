package dev.ide.desktop

import dev.ide.android.support.resources.ResourceItem
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The desktop preview's project-resource resolution: `R.string.x` → an aapt-shaped id → the resource's text,
 * so `stringResource(R.string.app_name)` renders the project's string instead of crashing on the synthetic
 * `R`. The interpreter resolves the id via [DesktopPreviewResources.rClassField] and the text via [string].
 */
class DesktopPreviewResourcesTest {

    private val repo = ResourceRepository(
        items = listOf(
            ResourceItem(ResourceType.STRING, "app_name", value = "MyApp"),
            ResourceItem(ResourceType.STRING, "greeting", value = "Hello"),
        ),
    )
    private val res = DesktopPreviewResources(repo, namespace = "com.example", density = 1f)

    @Test
    fun rStringFieldResolvesToItsIdThenToItsText() {
        val id = assertNotNull(res.rClassField("com.example.R.string", "app_name"), "R.string.app_name → id")
        assertEquals("MyApp", res.string(id), "the id should resolve back to the string value")
    }

    @Test
    fun unknownResourceOrForeignNamespaceYieldsNull() {
        assertNull(res.rClassField("com.example.R.string", "missing"), "an unknown string field → null")
        assertNull(res.rClassField("other.R.string", "app_name"), "another module's R → null")
        assertNull(res.rClassField("com.example.R.layout", "app_name"), "wrong resource type → null")
    }
}
