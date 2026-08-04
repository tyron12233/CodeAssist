package dev.ide.core.customize

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the persistence + merge backbone of the Symbols & Macros feature: shipped defaults, JSON round-trip
 * (including escaping), the project-over-global override, macro merge/filter semantics, and tolerant loading of
 * a corrupt file. This locks the on-disk format (the expensive-to-change piece) before any UI is built on it.
 */
class EditorCustomizationStoreTest {

    private fun tempFile(name: String): Path = Files.createTempDirectory("ec").resolve(name)

    @Test
    fun fallsBackToShippedDefaultsWhenNothingDefined() {
        val g = tempFile("g.json")
        val store = EditorCustomizationStore({ g }) { null }
        assertEquals(DefaultCustomizations.SYMBOLS, store.effectiveSymbols())
        // No user files → no user macros (the shipped built-ins are NOT folded into the user set).
        assertTrue(store.userMacros("java").isEmpty())
    }

    @Test
    fun symbolsRoundTripThroughDisk() {
        val g = tempFile("g.json")
        // A compound key and one whose insert contains quotes — exercises multi-char inserts + JSON escaping.
        val syms = listOf(SymbolKeyDef.of("->"), SymbolKeyDef("printf", "System.out.printf(\"\");"))
        EditorCustomizationStore({ g }) { null }.save(CustomizationScope.GLOBAL, CustomizationSet(symbols = syms))

        // A fresh store reads the same file — nothing cached across instances.
        val reopened = EditorCustomizationStore({ g }) { null }
        assertEquals(syms, reopened.effectiveSymbols())
    }

    @Test
    fun projectSymbolsOverrideGlobal() {
        val g = tempFile("g.json")
        val p = tempFile("p.json")
        val store = EditorCustomizationStore({ g }) { p }
        store.save(CustomizationScope.GLOBAL, CustomizationSet(symbols = listOf(SymbolKeyDef.of("G"))))

        // No project symbols yet → the global set is effective.
        assertEquals(listOf(SymbolKeyDef.of("G")), store.effectiveSymbols())

        store.save(CustomizationScope.PROJECT, CustomizationSet(symbols = listOf(SymbolKeyDef.of("P"))))
        assertEquals(listOf(SymbolKeyDef.of("P")), store.effectiveSymbols())
    }

    @Test
    fun macrosMergeByAbbreviationWithProjectWinning() {
        val g = tempFile("g.json")
        val p = tempFile("p.json")
        val store = EditorCustomizationStore({ g }) { p }
        store.save(
            CustomizationScope.GLOBAL,
            CustomizationSet(macros = listOf(MacroDef("sout", "global", languages = listOf("java")))),
        )
        store.save(
            CustomizationScope.PROJECT,
            CustomizationSet(macros = listOf(MacroDef("sout", "project", languages = listOf("java")))),
        )
        val merged = store.userMacros("java")
        assertEquals(1, merged.size)
        assertEquals("project", merged.single().template)
    }

    @Test
    fun userMacrosFilterByLanguageKeepingDisabled() {
        val g = tempFile("g.json")
        val store = EditorCustomizationStore({ g }) { null }
        store.save(
            CustomizationScope.GLOBAL,
            CustomizationSet(
                macros = listOf(
                    MacroDef("a", "x", languages = listOf("kotlin")),          // kotlin-only
                    MacroDef("b", "y", languages = emptyList()),               // all languages
                    MacroDef("c", "z", languages = listOf("java"), enabled = false), // java, disabled — kept
                ),
            ),
        )
        // userMacros filters by language but KEEPS disabled entries (the contributor removes those items).
        assertEquals(listOf("b", "c"), store.userMacros("java").map { it.abbreviation }.sorted())
        assertEquals(listOf("a", "b"), store.userMacros("kotlin").map { it.abbreviation }.sorted())
    }

    @Test
    fun exportImportPreservesAllMacroFields() {
        val g = tempFile("g.json")
        val store = EditorCustomizationStore({ g }) { null }
        val macro = MacroDef(
            abbreviation = "hdr",
            template = "// \$FILE\$ - \$DATE\$\n\$END\$",
            description = "file header",
            languages = listOf("java", "kotlin"),
            enabled = true,
            builtIn = false,
            receiverType = "java.lang.String",
            static = true,
        )
        store.save(CustomizationScope.GLOBAL, CustomizationSet(macros = listOf(macro)))

        val json = store.exportJson(CustomizationScope.GLOBAL)
        val decoded = CustomizationCodec.decode(json)
        assertEquals(listOf(macro), decoded.macros)
        // A macros-only set leaves symbols undefined (falls back), not empty.
        assertNull(decoded.symbols)
    }

    @Test
    fun corruptFileLoadsAsEmptyAndFallsBack() {
        val g = tempFile("g.json")
        g.writeText("{ this is not ] json")
        val store = EditorCustomizationStore({ g }) { null }
        assertEquals(DefaultCustomizations.SYMBOLS, store.effectiveSymbols())
    }
}
