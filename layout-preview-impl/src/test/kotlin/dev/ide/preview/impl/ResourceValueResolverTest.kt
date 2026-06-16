package dev.ide.preview.impl

import dev.ide.android.support.resources.ResourceItem
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
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
        assertNull(res.resolve("?attr/colorPrimary", ValueFormat.COLOR))
    }
}
