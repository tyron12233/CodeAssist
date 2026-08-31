package dev.ide.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Ext(val name: String, override val languages: Set<LanguageId>) : LanguageScoped {
    override fun toString(): String = name
}

private val JAVA = LanguageId("java")
private val KOTLIN = LanguageId("kotlin")
private val MYLANG = LanguageId("mylang")

class LanguageExtensionIndexTest {

    @Test
    fun `resolves the extensions that named the language`() {
        val java = Ext("java", setOf(JAVA))
        val kotlin = Ext("kotlin", setOf(KOTLIN))
        val index = LanguageExtensionIndex(listOf(java, kotlin))

        assertEquals(listOf(java), index.forLanguage(JAVA))
        assertEquals(listOf(kotlin), index.forLanguage(KOTLIN))
    }

    @Test
    fun `an extension naming no language applies to every language`() {
        val java = Ext("java", setOf(JAVA))
        val everywhere = Ext("everywhere", emptySet())
        val index = LanguageExtensionIndex(listOf(java, everywhere))

        assertEquals(listOf(java, everywhere), index.forLanguage(JAVA))
        // Including a language nothing named: the cross-cutting one still runs.
        assertEquals(listOf(everywhere), index.forLanguage(MYLANG))
    }

    @Test
    fun `a language nothing named and no cross-cutting extension resolves to nothing`() {
        val index = LanguageExtensionIndex(listOf(Ext("java", setOf(JAVA))))
        assertTrue(index.forLanguage(MYLANG).isEmpty())
    }

    @Test
    fun `preserves registration order, so a keyed lookup matches what a flat filter produced`() {
        val all = listOf(
            Ext("a", setOf(JAVA)),
            Ext("b", emptySet()),
            Ext("c", setOf(JAVA, KOTLIN)),
            Ext("d", setOf(KOTLIN)),
        )
        val index = LanguageExtensionIndex(all)
        for (language in listOf(JAVA, KOTLIN, MYLANG)) {
            assertEquals(
                all.filter { it.appliesTo(language) },
                index.forLanguage(language),
                "keyed lookup must equal the flat filter for ${language.id}",
            )
        }
    }

    @Test
    fun `an extension naming several languages resolves under each`() {
        val both = Ext("both", setOf(JAVA, KOTLIN))
        val index = LanguageExtensionIndex(listOf(both))
        assertEquals(listOf(both), index.forLanguage(JAVA))
        assertEquals(listOf(both), index.forLanguage(KOTLIN))
        assertEquals(setOf("java", "kotlin"), index.declaredLanguages)
    }

    @Test
    fun `an empty index resolves to nothing without throwing`() {
        val index = LanguageExtensionIndex(emptyList<Ext>())
        assertTrue(index.forLanguage(JAVA).isEmpty())
        assertTrue(index.declaredLanguages.isEmpty())
    }

    @Test
    fun `appliesTo treats an empty language set as every language`() {
        assertTrue(Ext("any", emptySet()).appliesTo(MYLANG))
        assertTrue(Ext("java", setOf(JAVA)).appliesTo(JAVA))
        assertTrue(!Ext("java", setOf(JAVA)).appliesTo(MYLANG))
    }
}
