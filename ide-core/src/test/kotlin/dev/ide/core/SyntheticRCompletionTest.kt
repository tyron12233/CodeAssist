package dev.ide.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end proof of the "light class" wiring through the real engine: the Android demo's `app` module
 * (namespace `com.example.app`, depending on the `feature` android-lib) gets a synthetic `R` generated
 * from its actual resources. Completing `R.string.` must list `greeting` (app's own res) AND
 * `feature_title` (the dependency's res) — covering the EP → provider → dependency-res merge → source
 * emit → name-environment overlay → completion path. No Android SDK needed: `R` is synthetic.
 */
class SyntheticRCompletionTest {

    private val root = createTempDirectory("synthetic-r")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun rMembersCompleteFromRealResourcesIncludingDependencies() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        val probe = root.resolve("app/src/main/java/com/example/app/Probe.java")
        val text = "package com.example.app;\nclass Probe { void m() { int x = R.string.; } }"
        val offset = text.indexOf("R.string.") + "R.string.".length

        val labels = s.complete(probe, text, offset).items.map { it.insertText.substringBefore('(') }
        assertTrue("greeting" in labels, "app's own R.string.greeting expected: $labels")
        assertTrue("feature_title" in labels, "dependency android-lib R.string.feature_title expected: $labels")
    }

    @Test
    fun rExposesResourceTypeClasses() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        val probe = root.resolve("app/src/main/java/com/example/app/Probe.java")
        val text = "package com.example.app;\nclass Probe { void m() { Object o = R.; } }"
        val offset = text.indexOf("R.") + "R.".length

        val labels = s.complete(probe, text, offset).items.map { it.insertText.substringBefore('(') }
        assertTrue("string" in labels, "R.string type expected: $labels")
        assertTrue("color" in labels, "R.color (from app colors.xml) expected: $labels")
    }

    @Test
    fun kotlinSeesSyntheticRButNotKotlinFacades() {
        // The SAME synthetic R must resolve for a KOTLIN file (the Kotlin backend, not JDT): `R.string.`
        // lists the app's own + dependency resources. And the Kotlin `<File>Kt` facades are NOT contributed
        // to the Kotlin backend, so `MainActivityKt` (a facade of the demo's own Kotlin, were one emitted)
        // never appears as a type — a Kotlin file calls its top-level declarations directly.
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        val probe = root.resolve("app/src/main/kotlin/com/example/app/Probe.kt")

        val rText = "package com.example.app\nfun m() { val x = R.string. }"
        val rOffset = rText.indexOf("R.string.") + "R.string.".length
        val rLabels = s.complete(probe, rText, rOffset).items.map { it.symbol?.name ?: it.label }
        assertTrue("greeting" in rLabels, "Kotlin: app's own R.string.greeting expected: $rLabels")
        assertTrue("feature_title" in rLabels, "Kotlin: dependency R.string.feature_title expected: $rLabels")

        // No Kotlin file facade leaks into Kotlin type completion.
        val fText = "package com.example.app\nfun m() { val x = MainActivityK }"
        val fOffset = fText.indexOf("MainActivityK") + "MainActivityK".length
        val fLabels = s.complete(probe, fText, fOffset).items.map { it.symbol?.name ?: it.label }
        assertTrue(fLabels.none { it.endsWith("Kt") }, "no Kotlin file facade as a Kotlin type: $fLabels")
    }

    @Test
    fun kotlinResolvesFrameworkAndroidR() {
        // `android.R` is the FRAMEWORK R from android.jar (distinct from the app's synthetic `com.example.app.R`).
        // A package-qualified type used directly must resolve, exposing its nested resource-type classes and
        // their fields — from the classpath index (in "dumb mode", before the index is ready, classpath symbols
        // intentionally don't resolve, so wait for the build rather than falling back to a live bytecode read).
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        awaitIndexed(s)
        val probe = root.resolve("app/src/main/kotlin/com/example/app/Probe.kt")

        val onR = kotlinLabels(s, probe, "package com.example.app\nfun m() { val x = android.R. }", "android.R.")
        assertTrue("string" in onR, "android.R.string nested class expected: $onR")
        assertTrue("layout" in onR, "android.R.layout nested class expected: $onR")

        val onString = kotlinLabels(s, probe, "package com.example.app\nfun m() { val x = android.R.string. }", "android.R.string.")
        assertTrue(onString.isNotEmpty(), "android.R.string.* framework ids expected, got none")
    }

    @Test
    fun kotlinPackageCompletion() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        awaitIndexed(s)
        val probe = root.resolve("app/src/main/kotlin/com/example/app/Probe.kt")

        // `java.ut<caret>` → the sub-package `util`.
        val subPkgs = kotlinLabels(s, probe, "package com.example.app\nfun m() { val x = java.ut }", "java.ut")
        assertTrue("util" in subPkgs, "java.ut should complete the sub-package 'util': $subPkgs")

        // `java.util.<caret>` → types in the package (and sub-packages).
        val inUtil = kotlinLabels(s, probe, "package com.example.app\nfun m() { val x = java.util. }", "java.util.")
        assertTrue("List" in inUtil, "java.util. should list the type 'List': ${inUtil.take(30)}")
    }

    private fun kotlinLabels(s: IdeServices, probe: Path, text: String, anchor: String): List<String> {
        val offset = text.indexOf(anchor) + anchor.length
        return s.complete(probe, text, offset).items.map { it.insertText.substringBefore('(') }
    }

    private fun awaitIndexed(ide: IdeServices, timeoutMs: Long = 180_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (ide.indexStatus.value.message != "Indexed" && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    @Test
    fun buildConfigMembersComplete() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        val probe = root.resolve("app/src/main/java/com/example/app/Probe.java")
        val text = "package com.example.app;\nclass Probe { void m() { Object o = BuildConfig.; } }"
        val offset = text.indexOf("BuildConfig.") + "BuildConfig.".length

        val labels = s.complete(probe, text, offset).items.map { it.insertText.substringBefore('(') }
        assertTrue("DEBUG" in labels, "BuildConfig.DEBUG expected: $labels")
        assertTrue("APPLICATION_ID" in labels, "BuildConfig.APPLICATION_ID expected: $labels")
    }

    @Test
    fun resourceXmlFlagsOnlyUnresolvedLocalReferences() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        val layout = root.resolve("app/src/main/res/layout/probe.xml")
        val text = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:text="@string/greeting"
                android:hint="@string/does_not_exist"
                android:theme="@android:style/Theme.Material" />
        """.trimIndent()

        val messages = s.analyzeDiagnostics(layout, text).map { it.message }
        assertTrue(messages.any { it.contains("string/does_not_exist") }, "unknown local resource flagged: $messages")
        assertTrue(messages.none { it.contains("greeting") }, "valid local resource not flagged: $messages")
        assertTrue(messages.none { it.contains("Theme.Material") }, "@android: framework ref not flagged: $messages")
    }

    @Test
    fun goToDefinitionResolvesResourceFromXmlAndJava() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }

        // XML: @string/greeting → its <string name="greeting"> declaration.
        val layout = root.resolve("app/src/main/res/layout/probe.xml")
        val xml = """<TextView xmlns:android="http://schemas.android.com/apk/res/android" android:text="@string/greeting"/>"""
        val xmlTarget = s.definitionAt(layout, xml, xml.indexOf("greeting"))
        assertNotNull(xmlTarget, "@string/greeting should resolve")
        assertTrue(xmlTarget!!.first.toString().endsWith(".xml"))
        assertTrue(Files.readString(xmlTarget.first).substring(xmlTarget.second).contains("greeting"))

        // Java: R.string.greeting → the same declaration.
        val probe = root.resolve("app/src/main/java/com/example/app/Probe.java")
        val java = "package com.example.app;\nclass Probe { void m() { int x = R.string.greeting; } }"
        val javaTarget = s.definitionAt(probe, java, java.indexOf("greeting"))
        assertNotNull(javaTarget, "R.string.greeting should resolve")
        assertTrue(javaTarget!!.first.toString().endsWith(".xml"))

        // A framework reference has no local definition.
        assertNull(s.definitionAt(layout, """<View android:text="@android:string/ok"/>""", 25))
    }
}
