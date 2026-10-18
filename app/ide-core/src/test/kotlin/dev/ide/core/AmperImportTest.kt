package dev.ide.core

import dev.ide.core.amper.AmperProjectImporter
import dev.ide.core.amper.AmperYaml
import dev.ide.core.gradle.GradleKmp
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.SyncReason
import dev.ide.model.sync.SyncRequest
import dev.ide.platform.ProgressReporter
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JetBrains Amper projects open from their YAML: the shapes of JetBrains' own `codeviewer` sample (a `kmp/lib`
 * with an alias and platform-qualified dependencies, an `android/app`, a `jvm/app`, and an `ios/app` that has
 * nothing to build here).
 */
class AmperImportTest {

    @Test
    fun readsTheYamlAmperWrites() {
        val yaml = AmperYaml.parse(
            """
            product:
              type: kmp/lib
              platforms: [android, jvm, iosArm64]   # trailing comment

            aliases:
              - jvmAndAndroid: [jvm, android]

            dependencies:
              - ${'$'}libs.compose.runtime
              - org.example:lib:1.0
            dependencies@android:
            - ${'$'}libs.androidx.core: exported
            - ./other: { scope: runtime-only }

            settings:
              compose:
                enabled: true
                resources:
                  packageName: "my.pkg.generated.resources"
            """.trimIndent()
        ) as Map<*, *>
        val product = yaml["product"] as Map<*, *>
        assertEquals("kmp/lib", product["type"])
        assertEquals(listOf("android", "jvm", "iosArm64"), product["platforms"])
        assertEquals(listOf(mapOf("jvmAndAndroid" to listOf("jvm", "android"))), yaml["aliases"])
        assertEquals(listOf("\$libs.compose.runtime", "org.example:lib:1.0"), yaml["dependencies"])
        assertEquals(
            listOf(mapOf("\$libs.androidx.core" to "exported"), mapOf("./other" to mapOf("scope" to "runtime-only"))),
            yaml["dependencies@android"],
            "a sequence may sit at its key's own indentation",
        )
        val compose = (yaml["settings"] as Map<*, *>)["compose"] as Map<*, *>
        assertEquals("true", compose["enabled"])
        assertEquals("my.pkg.generated.resources", (compose["resources"] as Map<*, *>)["packageName"])
    }

    private fun Path.w(rel: String, text: String) {
        val f = resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, text.trimIndent())
    }

    private fun writeCodeviewer(root: Path) {
        root.w("project.yaml", """
            modules:
              - androidApp
              - desktopApp
              - iosApp
              - shared
        """)
        root.w("libs.versions.toml", """
            [versions]
            compose-multiplatform = "1.12.1"
            androidx-activity = "1.13.0"
            [libraries]
            compose-foundation = { module = "org.jetbrains.compose.foundation:foundation", version.ref = "compose-multiplatform" }
            compose-runtime = { module = "org.jetbrains.compose.runtime:runtime", version.ref = "compose-multiplatform" }
            androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidx-activity" }
        """)
        root.w("shared/module.yaml", """
            product:
              type: kmp/lib
              platforms: [android, iosArm64, jvm]

            aliases:
              - jvmAndAndroid: [jvm, android]

            dependencies:
              - ${'$'}libs.compose.runtime
              - ${'$'}libs.compose.foundation

            dependencies@android:
              - ${'$'}libs.androidx.activity.compose: exported

            test-dependencies:
              - ${'$'}kotlin.test

            settings:
              compose:
                enabled: true
                resources:
                  packageName: codeviewer.shared.generated.resources
              android:
                namespace: org.jetbrains.codeviewer.common
                minSdk: 26
              kotlin:
                optIns: [androidx.compose.material3.ExperimentalMaterial3Api]
        """)
        root.w("shared/src/org/Common.kt", "package org")
        root.w("shared/src@android/org/Platform.android.kt", "package org")
        root.w("shared/src@jvm/org/Platform.jvm.kt", "package org")
        root.w("shared/src@jvmAndAndroid/org/Both.kt", "package org")
        root.w("shared/src@ios/org/Platform.ios.kt", "package org")
        root.w("shared/composeResources/font/Mono.ttf", "x")
        root.w("androidApp/module.yaml", """
            product: android/app
            dependencies:
              - //shared
              - ${'$'}libs.androidx.activity.compose
            settings:
              compose: enabled
              android:
                namespace: org.jetbrains.codeviewer
                minSdk: 26
                compileSdk:
                  apiLevel: 36
                versionName: "1.0"
        """)
        root.w("androidApp/src/AndroidManifest.xml", "<manifest/>")
        root.w("androidApp/res/values/strings.xml", "<resources/>")
        root.w("desktopApp/module.yaml", """
            product: jvm/app
            dependencies:
              - //shared
              - ${'$'}compose.desktop.currentOs
            settings:
              compose: enabled
        """)
        root.w("iosApp/module.yaml", "product: ios/app")
    }

    private fun resolve(root: Path) = runBlocking {
        AmperProjectImporter().resolve(SyncRequest(root, NoProgress, SyncReason.IMPORT))
    }

    @Test
    fun importsCodeviewerForItsAndroidTarget() {
        withTempDir("amper-codeviewer") { root ->
            writeCodeviewer(root)
            assertNotNull(AmperProjectImporter().detect(root))
            GradleKmp.setPreference(root, GradleKmp.Platform.ANDROID)
            val outcome = resolve(root)
            val model = assertNotNull(outcome.model, "${outcome.messages}")
            assertEquals(listOf("androidApp", "desktopApp", "shared"), model.modules.map { it.name }.sorted())
            assertTrue(outcome.messages.any { "iosApp" in it.text }, "the iOS app is named as left out")

            val shared = model.modules.single { it.name == "shared" }
            assertEquals("android-lib", shared.typeId)
            val main = shared.sourceSets.single { it.name == "main" }.roots
            assertEquals(setOf("src", "src@jvmAndAndroid", "src@android"), main.filterValues { ContentRole.SOURCE in it }.keys)
            val facets = shared.facets.associateBy { it.table }
            assertEquals(
                listOf("src", "src@jvmAndAndroid", "build/compose-resources/kotlin"),
                facets.getValue("kotlin").values["commonSourceRoots"],
            )
            assertEquals(listOf("androidx.compose.material3.ExperimentalMaterial3Api"), facets.getValue("kotlin").values["optIns"])
            assertEquals("codeviewer.shared.generated.resources", facets.getValue("composeResources").values["package"])
            assertEquals("org.jetbrains.codeviewer.common", facets.getValue("android").values["namespace"])
            // Both targets recorded, so the module switches natively like any multiplatform module.
            val targets = dev.ide.lang.kotlin.build.KotlinFacetCodec.decode(facets.getValue("kotlin").values).targets
            assertEquals(listOf("android", "jvm"), targets.map { it.name })
            assertTrue(targets.single { it.name == "jvm" }.roots.any { "src@jvm" in it }, "$targets")
            val libs = shared.dependencies.filterIsInstance<ExternalLibrary>().associate { it.coordinate to it.scope }
            assertEquals(DependencyScope.IMPLEMENTATION, libs["org.jetbrains.compose.foundation:foundation:1.12.1"])
            assertEquals(DependencyScope.API, libs["androidx.activity:activity-compose:1.13.0"], "exported is api")
            assertTrue(libs.keys.any { it.startsWith("org.jetbrains.kotlin:kotlin-test:") }, "$libs")

            val app = model.modules.single { it.name == "androidApp" }
            assertEquals("android-app", app.typeId)
            val android = app.facets.single { it.table == "android" }.values
            assertEquals("src/AndroidManifest.xml", android["manifest"])
            assertEquals(36L, (android["compileSdk"] as Number).toLong())
            assertEquals(setOf(ContentRole.ANDROID_RES), app.sourceSets.single { it.name == "main" }.roots["res"])
            assertTrue(app.dependencies.any { it is ExternalModuleRef && it.moduleName == "shared" })
            assertNull(app.facets.firstOrNull { it.table == "kotlin" }, "a single-platform module is not multiplatform")
        }
    }

    @Test
    fun theJvmTargetTakesTheJvmSourcesAndSkipsAndroidDependencies() {
        withTempDir("amper-codeviewer-jvm") { root ->
            writeCodeviewer(root)
            GradleKmp.setPreference(root, GradleKmp.Platform.JVM)
            val model = assertNotNull(resolve(root).model)
            val shared = model.modules.single { it.name == "shared" }
            assertEquals("java-lib", shared.typeId)
            val sources = shared.sourceSets.single { it.name == "main" }.roots.filterValues { ContentRole.SOURCE in it }.keys
            assertEquals(setOf("src", "src@jvmAndAndroid", "src@jvm"), sources)
            assertTrue(shared.dependencies.none { it is ExternalLibrary && "activity-compose" in it.coordinate })
            val desktop = model.modules.single { it.name == "desktopApp" }
            assertTrue(desktop.dependencies.any { it is ExternalLibrary && it.coordinate.startsWith("org.jetbrains.compose.desktop:desktop-jvm-") })
        }
    }

    private object NoProgress : ProgressReporter {
        override fun report(fraction: Double, message: String?) {}
        override fun checkCanceled() {}
        override val isCanceled = false
    }
}
