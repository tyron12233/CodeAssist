package dev.ide.core

import dev.ide.android.support.AndroidFacet
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end proof that ViewBinding classes resolve in the EDITOR before a build, through the real engine.
 *
 * ViewBinding's synthetic `<namespace>.databinding.<Layout>Binding` lives in a package (`.databinding`) with
 * no directory in any source root — unlike R/BuildConfig, which sit in the real `<namespace>` source package.
 * The injected element finder used to report only classes, never packages, so resolving the qualifier
 * `com.example.app.databinding` (as an import or a qualified type) found no package and the whole reference
 * stayed unresolved: completion and diagnostics couldn't see the binding even though the build generates it.
 */
class ViewBindingEditorResolutionTest {

    @Test
    fun bindingResolvesForCompletionAndAnalysisBeforeABuild() { withTempDir("ide-vb") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            // Turn on viewBinding for the app module (namespace com.example.app) and drop the synthetic caches
            // so the analyzer regenerates the binding classes — the same path the buildFeatures toggle takes.
            val project = ide.store.workspace.projects.first { p -> p.modules.any { it.name == "app" } }
            val app = project.modules.first { it.name == "app" }
            val facet = app.facets.get(AndroidFacet.KEY)!!
            project.beginModification().apply {
                module(app.id).putFacet(facet.copy(buildFeatures = facet.buildFeatures.copy(viewBinding = true)))
                commit()
            }
            ide.invalidateSyntheticClasses()

            // A layout with one @+id → ActivityMainBinding { TextView greeting; ... }.
            val layoutDir = dir.resolve("app/src/main/res/layout")
            Files.createDirectories(layoutDir)
            Files.writeString(
                layoutDir.resolve("activity_main.xml"),
                """
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                    <TextView android:id="@+id/greeting"/>
                </LinearLayout>
                """.trimIndent(),
            )

            val probe = dir.resolve("app/src/main/java/com/example/app/Probe.java")

            // Static factory completion on the imported binding type.
            val staticText = "package com.example.app;\n" +
                "import com.example.app.databinding.ActivityMainBinding;\n" +
                "class Probe { void m() { ActivityMainBinding.; } }"
            val staticOffset = staticText.indexOf("ActivityMainBinding.;") + "ActivityMainBinding.".length
            val staticLabels = runBlocking { ide.complete(probe, staticText, staticOffset) }
                .items.map { it.insertText.substringBefore('(') }
            assertTrue("inflate" in staticLabels, "ActivityMainBinding.inflate expected: $staticLabels")

            // Instance-field completion (the id from the layout).
            val fieldText = "package com.example.app;\n" +
                "import com.example.app.databinding.ActivityMainBinding;\n" +
                "class Probe { void m(ActivityMainBinding b) { b.; } }"
            val fieldOffset = fieldText.indexOf("b.;") + "b.".length
            val fieldLabels = runBlocking { ide.complete(probe, fieldText, fieldOffset) }
                .items.map { it.insertText.substringBefore('(') }
            assertTrue("greeting" in fieldLabels, "binding.greeting field expected: $fieldLabels")
            assertTrue("getRoot" in fieldLabels, "binding.getRoot() expected: $fieldLabels")

            // Diagnostics: the import + usage must NOT be flagged unresolved.
            val useText = "package com.example.app;\n" +
                "import com.example.app.databinding.ActivityMainBinding;\n" +
                "class Probe {\n" +
                "  void m(android.view.LayoutInflater inf) {\n" +
                "    ActivityMainBinding b = ActivityMainBinding.inflate(inf);\n" +
                "    b.greeting.toString();\n" +
                "  }\n" +
                "}"
            val diags = runBlocking { ide.analyzeDiagnostics(probe, useText) }.map { it.message }
            assertFalse(
                diags.any { "ActivityMainBinding" in it || "databinding" in it },
                "the ViewBinding import/usage must resolve (no unresolved-reference): $diags",
            )
        }
        dir.toFile().deleteRecursively()
    } }
}
