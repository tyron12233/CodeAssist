package dev.ide.android.layouteditor

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropertiesTest {

    private fun el(attrs: String) = parseTestLayout("<View $attrs/>").root!!

    @Test
    fun boxReadsSidesTheWayTheFrameworkResolvesThem() {
        val box = readBox(el("""android:layout_margin="8dp" android:layout_marginTop="16dp""""), BOX_MARGIN)
        assertEquals(BoxValues("8dp", "16dp", "8dp", "8dp"), box)
        assertNull(box.uniform)
        assertEquals("8dp", readBox(el("""android:padding="8dp""""), BOX_PADDING).uniform)
    }

    @Test
    fun uniformBoxCollapsesToTheShorthand() {
        val e = el("""android:layout_marginStart="4dp" android:layout_marginTop="8dp"""")
        val writes = boxWrites(e, BOX_MARGIN, BoxValues("16dp", "16dp", "16dp", "16dp")).toMap()
        assertEquals("16dp", writes["android:layout_margin"])
        assertTrue(writes.containsKey("android:layout_marginStart") && writes["android:layout_marginStart"] == null)
        assertTrue(writes.containsKey("android:layout_marginTop") && writes["android:layout_marginTop"] == null)
        // Sides that were never written are not "removed" again.
        assertTrue("android:layout_marginEnd" !in writes)
    }

    @Test
    fun unevenBoxSplitsIntoSidesAndDropsTheShorthand() {
        val e = el("""android:padding="8dp"""")
        val writes = boxWrites(e, BOX_PADDING, BoxValues("8dp", "16dp", "8dp", "16dp")).toMap()
        assertEquals(null, writes["android:padding"])
        assertTrue(writes.containsKey("android:padding"))
        assertEquals("16dp", writes["android:paddingTop"])
        assertEquals("8dp", writes["android:paddingStart"])
    }

    @Test
    fun gravityCellsRoundTrip() {
        for (r in 0..2) for (c in 0..2) assertEquals(r to c, gravityCell(gravityFor(r, c)))
        assertEquals("center", gravityFor(1, 1))
        assertEquals(0 to 2, gravityCell("top|end"))
        assertEquals(1 to 0, gravityCell("center_vertical|left"))
        assertNull(gravityCell(null))
    }

    @Test
    fun previewsSayWhatIsSet() {
        val e = el("""android:layout_width="wrap_content" android:layout_height="48dp" android:text="@string/go" android:layout_margin="16dp"""")
        val by = ALL_PROPERTIES.associateBy { it.key }
        assertEquals("wrap × 48dp", previewOf(by.getValue("size"), e, emptyMap()))
        assertEquals("“Go!”", previewOf(by.getValue("text"), e, mapOf("go" to "Go!")))
        assertEquals("16dp", previewOf(by.getValue("margin"), e, emptyMap()))
        assertNull(previewOf(by.getValue("padding"), e, emptyMap()))
    }

    @Test
    fun basicPropertiesKeepOnlyWhatTheViewAccepts() {
        val accepted = setOf("android:text", "android:layout_width", "android:layout_height", "android:layout_weight", "android:id")
        val keys = basicPropertiesFor("Button") { it in accepted }.map { it.key }
        assertEquals(listOf("text", "size", "weight", "id"), keys)
        assertEquals("android:src", basicPropertiesFor("ImageView") { it == "android:src" }.first().writeAttribute { it == "android:src" })
    }

    @Test
    fun scaleTypesPlaceTheImageWhereTheFrameworkWould() {
        val frame = Rect(0f, 0f, 100f, 50f)
        assertEquals(frame, scaleTypeRect("fitXY", frame, 0.75f))
        val start = scaleTypeRect("fitStart", frame, 0.75f)
        assertEquals(0f, start.left); assertEquals(50f, start.height)
        val end = scaleTypeRect("fitEnd", frame, 0.75f)
        assertEquals(100f, end.right)
        val crop = scaleTypeRect("centerCrop", frame, 0.75f)
        assertTrue(crop.width >= 100f && crop.height >= 50f)
        assertEquals("16dp", dp(16f)); assertEquals("0.5", dp(0.5f, "")); assertEquals(12f, dpNumber("12sp"))
    }

    @Test
    fun aDraftShowsItsEditsAndWritesNothingUntilFlushed() {
        val e = el("""android:text="Hi" android:layout_margin="8dp"""")
        val written = ArrayList<Pair<String, String?>>()
        val pc = PropertyContext(e, emptyMap(), true, { written += it }, { _, _, _ -> error("unused") }, { emptyList() }, { emptyList() })
        val draft = HashMap<String, String?>()
        val d = pc.draftOf(draft)

        d.set("android:text", "Hello")
        d.write(boxWrites(d::value, BOX_MARGIN, BoxValues("16dp", "16dp", "16dp", "16dp")))

        assertTrue(written.isEmpty(), "a draft wrote to the buffer: $written")
        assertEquals("Hello", d.value("android:text"))
        assertEquals("16dp", readBox(d::value, BOX_MARGIN).uniform)
        // The element itself is untouched.
        assertEquals("Hi", pc.value("android:text"))
        assertEquals("8dp", readBox(e, BOX_MARGIN).uniform)
    }
}
