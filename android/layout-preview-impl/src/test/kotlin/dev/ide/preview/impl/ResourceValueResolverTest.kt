package dev.ide.preview.impl

import dev.ide.android.support.resources.ResourceItem
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.android.support.resources.StyleData
import dev.ide.preview.ResolvedValue
import dev.ide.preview.ValueFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResourceValueResolverTest {

    private val repo = ResourceRepository(listOf(
        ResourceItem(ResourceType.COLOR, "primary", value = "#FF6200EE"),
        ResourceItem(ResourceType.COLOR, "alias", value = "@color/primary"),
        ResourceItem(ResourceType.DIMEN, "gap", value = "8dp"),
        ResourceItem(ResourceType.STRING, "greeting", value = "Hello"),
        ResourceItem(ResourceType.BOOL, "flag", value = "true"),
    ))
    private val res = ProjectPreviewResources(repo, density = 2f, scaledDensity = 3f)

    @Test fun `literal color`() {
        assertEquals(0xFF6200EE.toInt(), (res.resolve("#FF6200EE", ValueFormat.COLOR) as ResolvedValue.Color).argb)
    }

    @Test fun `color reference`() {
        assertEquals(0xFF6200EE.toInt(), (res.resolve("@color/primary", ValueFormat.COLOR) as ResolvedValue.Color).argb)
    }

    @Test fun `transitive color reference collapses to argb`() {
        assertEquals(0xFF6200EE.toInt(), (res.resolve("@color/alias", ValueFormat.COLOR) as ResolvedValue.Color).argb)
    }

    @Test fun `dimension reference honours density`() {
        assertEquals(16f, (res.resolve("@dimen/gap", ValueFormat.DIMENSION) as ResolvedValue.Dimension).px)
    }

    @Test fun `dp literal scales by density, sp by scaledDensity`() {
        assertEquals(32f, (res.resolve("16dp", ValueFormat.DIMENSION) as ResolvedValue.Dimension).px)
        assertEquals(42f, (res.resolve("14sp", ValueFormat.DIMENSION) as ResolvedValue.Dimension).px)
        assertEquals(10f, (res.resolve("10px", ValueFormat.DIMENSION) as ResolvedValue.Dimension).px)
    }

    @Test fun `string and bool references`() {
        assertEquals("Hello", (res.resolve("@string/greeting", ValueFormat.STRING) as ResolvedValue.Str).text)
        assertEquals(true, (res.resolve("@bool/flag", ValueFormat.BOOLEAN) as ResolvedValue.BoolV).v)
    }

    @Test fun `framework color via builtin table`() {
        assertEquals(0x00000000, (res.resolve("@android:color/transparent", ValueFormat.COLOR) as ResolvedValue.Color).argb)
    }

    @Test fun `drawable reference stays a Ref for the renderer to load`() {
        val v = res.resolve("@drawable/ic_logo", ValueFormat.REFERENCE)
        assertEquals(ResolvedValue.Ref("drawable", "ic_logo"), v)
    }

    @Test fun `unresolved reference is null`() {
        assertNull(res.resolve("@color/missing", ValueFormat.COLOR))
        assertNull(res.resolve("?attr/madeUpAttribute", ValueFormat.COLOR))
    }

    @Test fun `theme attribute falls back to framework defaults without a theme`() {
        // No themeName supplied — `actionBarSize`/`colorPrimary` resolve from the built-in Material table.
        assertEquals(112f, (res.resolve("?attr/actionBarSize", ValueFormat.DIMENSION) as ResolvedValue.Dimension).px) // 56dp @ density 2
        assertEquals(0xFF6200EE.toInt(), (res.resolve("?attr/colorPrimary", ValueFormat.COLOR) as ResolvedValue.Color).argb)
        assertEquals(0xFF6200EE.toInt(), (res.resolve("?android:attr/colorPrimary", ValueFormat.COLOR) as ResolvedValue.Color).argb)
    }

    @Test fun `theme attribute resolves against the project theme chain`() {
        val themed = ProjectPreviewResources(
            ResourceRepository(
                items = listOf(ResourceItem(ResourceType.COLOR, "brand", value = "#FF112233")),
                styles = mapOf("Theme.App" to StyleData(parent = "Theme.Material3.DayNight.NoActionBar", items = mapOf("colorPrimary" to "@color/brand"))),
            ),
            density = 2f, themeName = "Theme.App",
        )
        // The theme overrides the framework default; the transitive @color/brand collapses to ARGB.
        assertEquals(0xFF112233.toInt(), (themed.resolve("?attr/colorPrimary", ValueFormat.COLOR) as ResolvedValue.Color).argb)
        // Not defined by the theme — still served by the framework default table.
        assertEquals(112f, (themed.resolve("?attr/actionBarSize", ValueFormat.DIMENSION) as ResolvedValue.Dimension).px)
    }

    @Test fun `parsed AAR theme values are preferred over the framework default table`() {
        // Simulate a resolved AppCompat AAR: its theme styles + backing values are merged into the repository,
        // so the chain resolves the library's real values (not the built-in Material fallback). This is the
        // primary path — the hardcoded table only fires when nothing in the chain supplies the attribute.
        val themed = ProjectPreviewResources(
            ResourceRepository(
                items = listOf(
                    ResourceItem(ResourceType.COLOR, "brand", value = "#FF0066CC"),
                    ResourceItem(ResourceType.DIMEN, "abc_action_bar_default_height_material", value = "64dp"),
                ),
                styles = mapOf(
                    "Theme.App" to StyleData(parent = "Theme.AppCompat.Light.DarkActionBar", items = emptyMap()),
                    "Theme.AppCompat.Light.DarkActionBar" to StyleData(parent = "Base.Theme.AppCompat", items = mapOf("colorPrimary" to "@color/brand")),
                    "Base.Theme.AppCompat" to StyleData(parent = null, items = mapOf("actionBarSize" to "@dimen/abc_action_bar_default_height_material")),
                ),
            ),
            density = 2f, themeName = "Theme.App",
        )
        assertEquals(0xFF0066CC.toInt(), (themed.resolve("?attr/colorPrimary", ValueFormat.COLOR) as ResolvedValue.Color).argb) // library brand, not table purple
        assertEquals(128f, (themed.resolve("?attr/actionBarSize", ValueFormat.DIMENSION) as ResolvedValue.Dimension).px) // 64dp@2 from the AAR, not the table's 56dp
    }
}
