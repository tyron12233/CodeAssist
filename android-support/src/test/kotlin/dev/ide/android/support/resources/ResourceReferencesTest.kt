package dev.ide.android.support.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceReferencesTest {

    private fun ref(s: String) = ResourceReferences.scan(s).single()

    @Test
    fun parsesPlainTypedAndCreateReferences() {
        ref("@string/app_name").let {
            assertEquals(ResourceType.STRING, it.type); assertEquals("app_name", it.name)
            assertTrue(it.isLocal); assertFalse(it.create); assertFalse(it.themeAttr)
        }
        ref("@+id/submit").let {
            assertEquals(ResourceType.ID, it.type); assertEquals("submit", it.name); assertTrue(it.create)
        }
        // a name with dots is kept verbatim (validation sanitizes it to match R/aapt).
        assertEquals("Theme.App", ref("@style/Theme.App").name)
        // `@null` is a pseudo-resource: no type token.
        assertNull(ref("@null").type)
    }

    @Test
    fun handlesNamespacesAndThemeAttributes() {
        ref("@android:string/ok").let {
            assertEquals("android", it.packageName); assertTrue(it.isFramework); assertEquals(ResourceType.STRING, it.type)
        }
        ref("@com.example.lib:color/brand").let {
            assertEquals("com.example.lib", it.packageName); assertFalse(it.isLocal); assertEquals(ResourceType.COLOR, it.type)
        }
        ref("?attr/colorPrimary").let {
            assertTrue(it.themeAttr); assertEquals(ResourceType.ATTR, it.type); assertEquals("colorPrimary", it.name)
        }
        ref("?colorAccent").let {
            assertTrue(it.themeAttr); assertEquals(ResourceType.ATTR, it.type); assertEquals("colorAccent", it.name)
        }
        ref("?android:attr/textColorPrimary").let {
            assertTrue(it.themeAttr); assertEquals("android", it.packageName); assertEquals(ResourceType.ATTR, it.type)
        }
    }

    @Test
    fun flagsOnlyUnresolvedLocalReferences() {
        val repo = ResourceRepository(listOf(
            ResourceItem(ResourceType.STRING, "app_name"),
            ResourceItem(ResourceType.STRING, "greeting"),
            ResourceItem(ResourceType.STYLE, "Theme_App"),
        ))
        val xml = """
            <View android:text="@string/greeting"
                  android:hint="@string/typo"
                  android:label="@android:string/cancel"
                  android:id="@+id/new_id"
                  android:theme="?attr/colorPrimary"
                  style="@style/Theme.App"
                  android:src="@drawable/ic_logo" />
        """.trimIndent()

        val problems = ResourceReferences.problems(xml, repo)
        assertEquals(1, problems.size, "only @string/typo is an unresolved local ref: $problems")
        assertTrue(problems.single().message.contains("string/typo"))
        // and its span points at the reference text:
        assertEquals("@string/typo", xml.substring(problems.single().start, problems.single().end))
    }

    @Test
    fun doesNotFlagTypesWithNoLocalResources() {
        // DRAWABLE has no entries in this repo → drawable refs are assumed library/framework-provided, not flagged.
        val repo = ResourceRepository(listOf(ResourceItem(ResourceType.STRING, "app_name")))
        assertTrue(ResourceReferences.problems("""<View android:src="@drawable/ic_unknown"/>""", repo).isEmpty())
    }

    @Test
    fun resolvesDottedStyleNamesAgainstSanitizedIds() {
        val repo = ResourceRepository(listOf(ResourceItem(ResourceType.STYLE, "Theme_App")))
        assertTrue(ResourceReferences.problems("""<View style="@style/Theme.App"/>""", repo).isEmpty(), "Theme.App ~ Theme_App")
        assertEquals(1, ResourceReferences.problems("""<View style="@style/Missing"/>""", repo).size)
    }
}
