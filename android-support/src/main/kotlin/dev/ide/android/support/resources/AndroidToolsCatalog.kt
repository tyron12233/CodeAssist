package dev.ide.android.support.resources

import dev.ide.android.support.metadata.AttributeSpec

/**
 * The `tools:` namespace (`http://schemas.android.com/tools`) — design-time-only attributes the build strips
 * but the IDE/layout-preview honors. Two kinds are completed:
 *  - **tools-specific** attributes that have no `android:` equivalent (`tools:context`, `tools:listitem`,
 *    `tools:showIn`, `tools:ignore`, …), curated here with their value shape (this catalog), and
 *  - **design-time overrides** of any `android:`/`app:` attribute (`tools:text`, `tools:visibility`, …),
 *    which the completion adapter derives by re-prefixing the layout metadata — so they're not listed here.
 *
 * A hand-built table is right: the tools schema is small, stable, and lives in AAPT/AS tooling, not in
 * `attrs.xml`. Value shapes reuse [AttributeSpec] so the completion adapter treats them identically.
 */
object AndroidToolsCatalog {

    const val URI = "http://schemas.android.com/tools"

    /** Attributes valid on any element. */
    private val COMMON: List<AttributeSpec> = listOf(
        AttributeSpec("tools:context"),                                            // the activity/fragment class
        AttributeSpec("tools:ignore"),                                             // lint issue id(s) to suppress
        AttributeSpec("tools:targetApi"),                                          // API level / codename
        AttributeSpec("tools:showIn", resourceTypes = listOf(ResourceType.LAYOUT)),
        AttributeSpec("tools:menu", resourceTypes = listOf(ResourceType.MENU)),
        AttributeSpec("tools:layout", resourceTypes = listOf(ResourceType.LAYOUT)),
        AttributeSpec("tools:visibility", enumValues = listOf("visible", "invisible", "gone")),
        AttributeSpec("tools:viewBindingIgnore", boolean = true),
        AttributeSpec("tools:viewBindingType"),
    )

    /** Attributes that only make sense on a list-like container (RecyclerView / AdapterView). */
    private val LIST: List<AttributeSpec> = listOf(
        AttributeSpec("tools:listitem", resourceTypes = listOf(ResourceType.LAYOUT)),
        AttributeSpec("tools:listheader", resourceTypes = listOf(ResourceType.LAYOUT)),
        AttributeSpec("tools:listfooter", resourceTypes = listOf(ResourceType.LAYOUT)),
        AttributeSpec("tools:itemCount"),                                          // a plain integer literal
    )

    private val OPEN_DRAWER = AttributeSpec("tools:openDrawer", enumValues = listOf("start", "end", "left", "right"))

    private val ALL: List<AttributeSpec> = COMMON + LIST + OPEN_DRAWER

    /** The tools-specific attributes worth offering on [tag] (light, name-based context gating). */
    fun attributesFor(tag: String?): List<AttributeSpec> {
        val simple = tag?.substringAfterLast('.') ?: ""
        val out = ArrayList(COMMON)
        if (isListLike(simple)) out += LIST
        if (simple.contains("DrawerLayout")) out += OPEN_DRAWER
        return out
    }

    /** The spec for a tools-specific attribute (for value completion), or null if it's not one we curate. */
    fun attribute(attributeName: String?): AttributeSpec? =
        attributeName?.let { name -> ALL.firstOrNull { it.name == name } }

    private fun isListLike(simple: String): Boolean =
        simple.contains("RecyclerView") || simple.contains("ListView") || simple.contains("GridView") ||
            simple.contains("ViewPager") || simple == "Spinner" || simple == "Gallery" || simple == "StackView"
}
