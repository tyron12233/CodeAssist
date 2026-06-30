package dev.ide.core

import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiFacetConfig
import dev.ide.ui.backend.UiModuleConfigEdit
import dev.ide.ui.backend.UiSearchOptions
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the model-API-driven Module Settings editor end-to-end: read a module's config (incl. the
 * generically-derived Android facet panel), mutate the language level + an Android field, persist through
 * the transaction, and confirm it survives a reload from `module.toml`. Also covers find-in-files.
 */
class ModuleConfigTest {

    @Test
    fun readsCoreFieldsAndAGenericAndroidFacetPanel() {
        val dir = Files.createTempDirectory("ide-cfg")
        IdeServices.bootstrapDemo(dir).use { ide ->
            val config = assertNotNull(ide.moduleService.getModuleConfig("app"), "app config should load")
            assertEquals("app", config.name)
            assertTrue(config.languageLevels.contains(config.languageLevel), "current level is one of the options")
            assertTrue(config.sourceSets.isNotEmpty(), "android-app has source sets")

            val android = assertNotNull(config.facets.firstOrNull { it.table == "android" }, "android facet panel present")
            // Scalars derived by type: namespace=Text, compileSdk/minSdk=Number, isApplication=Bool.
            assertTrue(android.fields.any { it is UiConfigField.Text && it.key == "namespace" }, "namespace → Text")
            assertTrue(android.fields.any { it is UiConfigField.Number && it.key == "minSdk" }, "minSdk → Number")
            assertTrue(android.fields.any { it is UiConfigField.Bool && it.key == "isApplication" }, "isApplication → Bool")
            // Inline-table arrays derived as a TableList (build types).
            assertTrue(android.fields.any { it is UiConfigField.TableList && it.key == "buildTypes" }, "buildTypes → TableList")

            // A pure-Java module has no Android panel — extensibility, not hardcoding.
            val core = assertNotNull(ide.moduleService.getModuleConfig("core"))
            assertTrue(core.facets.none { it.table == "android" }, "java-lib has no android facet")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun editingLanguageLevelAndAFacetFieldPersistsAndReloads() {
        val dir = Files.createTempDirectory("ide-cfg-edit")
        IdeServices.bootstrapDemo(dir).use { ide ->
            val before = assertNotNull(ide.moduleService.getModuleConfig("app"))
            val android = assertNotNull(before.facets.firstOrNull { it.table == "android" })
            val originalNamespace = (android.fields.first { it.key == "namespace" } as UiConfigField.Text).value

            // Send the FULL facet map (as the UI does), overriding minSdk; flip the language level too.
            val edit = UiModuleConfigEdit(
                languageLevel = "JAVA_21",
                facetValues = mapOf("android" to android.toValues(overrides = mapOf("minSdk" to 24L))),
            )
            val result = ide.moduleService.updateModuleConfig("app", edit)
            assertTrue(result.success, "update should succeed: ${result.message}")

            // Visible in the live model immediately.
            val after = assertNotNull(ide.moduleService.getModuleConfig("app"))
            assertEquals("JAVA_21", after.languageLevel)
            val minSdk = (after.facets.first { it.table == "android" }.fields.first { it.key == "minSdk" } as UiConfigField.Number).value
            assertEquals(24L, minSdk)
            // The untouched field survived the round-trip (not reset to a default).
            val ns = (after.facets.first { it.table == "android" }.fields.first { it.key == "namespace" } as UiConfigField.Text).value
            assertEquals(originalNamespace, ns)

            // module.toml on disk reflects it.
            assertTrue(Files.walk(dir).use { s -> s.anyMatch { it.fileName?.toString() == "module.toml" } })
        }

        // Re-open the workspace from disk → the edit persisted through module.toml.
        IdeServices.open(dir).use { reopened ->
            val reloaded = assertNotNull(reopened.moduleService.getModuleConfig("app"))
            assertEquals("JAVA_21", reloaded.languageLevel, "language level persisted")
            val minSdk = (reloaded.facets.first { it.table == "android" }.fields.first { it.key == "minSdk" } as UiConfigField.Number).value
            assertEquals(24L, minSdk, "minSdk persisted to module.toml")
        }
        dir.toFile().deleteRecursively()
    }

    // Mirror what the UI does on Save: collapse the rendered fields back into the codec's value map,
    // applying any edits. Proves the generic round-trip (incl. the buildTypes TableList) is lossless.
    private fun UiFacetConfig.toValues(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        fields.associate { f -> f.key to (overrides[f.key] ?: f.rawValue()) }

    private fun UiConfigField.rawValue(): Any? = when (this) {
        is UiConfigField.Text -> value
        is UiConfigField.Number -> value
        is UiConfigField.Bool -> value
        is UiConfigField.StringList -> values
        is UiConfigField.TableList -> rows.map { row -> row.associate { it.key to it.rawValue() } }
    }

    @Test
    fun findInFilesMatchesProjectSources() {
        val dir = Files.createTempDirectory("ide-find")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            // "package" appears in every Java source — a stable probe.
            val hits = ide.search.findInFiles("package", UiSearchOptions(), limit = 200)
            assertTrue(hits.isNotEmpty(), "find-in-files should match project sources")
            val first = hits.first()
            assertTrue(first.line >= 1 && first.offset >= 0, "match carries a navigable line/offset")
            assertTrue("package" in first.lineText.lowercase(), "the matched line contains the query")

            // Case sensitivity is honored: an all-caps query won't match lowercase 'package'.
            val none = ide.search.findInFiles("PACKAGE", UiSearchOptions(caseSensitive = true), limit = 50)
            assertTrue(none.none { "package " in it.lineText }, "case-sensitive search shouldn't match lowercase")
        }
        dir.toFile().deleteRecursively()
    }
}
