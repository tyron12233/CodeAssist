package dev.ide.android.support.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RIdAssignmentTest {

    private val repo = ResourceRepository(
        items = listOf(
            ResourceItem(ResourceType.COLOR, "primary", value = "#FF6200EE"),
            ResourceItem(ResourceType.COLOR, "accent", value = "#FF03DAC5"),
            ResourceItem(ResourceType.ATTR, "barColor"),
            ResourceItem(ResourceType.ATTR, "maxValue"),
            ResourceItem(ResourceType.STYLEABLE, "MyChart"),
        ),
        styleableAttrs = mapOf("MyChart" to listOf("barColor", "maxValue")),
    )
    private val ids = RIdAssignment(repo)

    @Test fun `ids are in the 0x7f package space and unique per entry`() {
        val primary = assertNotNull(ids.id(ResourceType.COLOR, "primary"))
        val accent = assertNotNull(ids.id(ResourceType.COLOR, "accent"))
        assertEquals(0x7f, primary ushr 24)
        assertNotEquals(primary, accent)
    }

    @Test fun `assignment is deterministic across instances`() {
        val again = RIdAssignment(repo)
        assertEquals(ids.id(ResourceType.COLOR, "primary"), again.id(ResourceType.COLOR, "primary"))
        assertEquals(ids.id(ResourceType.ATTR, "barColor"), again.id(ResourceType.ATTR, "barColor"))
    }

    @Test fun `styleable array holds its attr ids in declaration order`() {
        val array = ids.styleableArray(repo, "MyChart")
        assertEquals(listOf(ids.id(ResourceType.ATTR, "barColor"), ids.id(ResourceType.ATTR, "maxValue")), array.toList())
    }

    @Test fun `id round-trips back to type and name for the bridge reverse lookup`() {
        val id = assertNotNull(ids.id(ResourceType.ATTR, "barColor"))
        assertEquals(ResourceType.ATTR to "barColor", ids.nameOf(id))
    }
}
