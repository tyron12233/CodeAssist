package dev.ide.core

import dev.ide.model.DependencyScope
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Import a modern Kotlin-DSL + version-catalog Compose sample (modelled on **Jetsnack** from
 * android/compose-samples) and assert the tolerant reader captures the two things a Compose app can't build
 * without: the SDK levels declared through `libs.versions.*` (not literals), and the Compose **BOM** that is
 * bound to a local `val` and only then applied (`implementation(composeBom)`) — the BOM supplies versions for
 * the versionless `androidx.compose.*` libraries, so dropping it used to make every one of them unresolvable.
 */
class JetSnackImportComposeTest {

    private fun writeJetsnackLike(dir: Path) {
        fun w(rel: String, text: String) {
            val f = dir.resolve(rel)
            Files.createDirectories(f.parent)
            f.writeText(text.trimIndent())
        }
        w("settings.gradle.kts", """
            rootProject.name = "Jetsnack"
            include(":app")
        """)
        w("gradle/libs.versions.toml", """
            [versions]
            androidGradlePlugin = "9.2.1"
            kotlin = "2.3.21"
            compileSdk = "37"
            minSdk = "23"
            targetSdk = "36"
            androidx-compose-bom = "2026.06.00"
            androidx-corektx = "1.19.0"
            coil = "2.7.0"

            [libraries]
            androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "androidx-compose-bom" }
            androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidx-corektx" }
            androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
            androidx-compose-ui = { module = "androidx.compose.ui:ui" }
            androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
            coil-kt-compose = { module = "io.coil-kt:coil-compose", version.ref = "coil" }

            [plugins]
            android-application = { id = "com.android.application", version.ref = "androidGradlePlugin" }
            compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
        """)
        w("app/build.gradle.kts", """
            plugins {
                alias(libs.plugins.android.application)
                alias(libs.plugins.compose)
            }
            android {
                compileSdk = libs.versions.compileSdk.get().toInt()
                namespace = "com.example.jetsnack"
                defaultConfig {
                    applicationId = "com.example.jetsnack"
                    minSdk = libs.versions.minSdk.get().toInt()
                    targetSdk = libs.versions.targetSdk.get().toInt()
                }
                buildFeatures { compose = true }
            }
            dependencies {
                val composeBom = platform(libs.androidx.compose.bom)
                implementation(composeBom)
                androidTestImplementation(composeBom)

                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.ui)
                debugImplementation(libs.androidx.compose.ui.tooling)
                implementation(libs.coil.kt.compose)
            }
        """)
        w("app/src/main/AndroidManifest.xml", """<manifest package="com.example.jetsnack"/>""")
    }

    @Test
    fun capturesComposeBomAndCatalogSdkLevels() {
        withTempDir("jetsnack-import") { dir ->
            writeJetsnackLike(dir)
            val spec = assertNotNull(GradleImport.parse(dir), "project should import")
            assertEquals("Jetsnack", spec.name)
            val app = spec.modules.single { it.name == "app" }

            assertEquals(GradleImport.Kind.ANDROID_APP, app.kind, "android app plugin (via catalog alias)")
            assertTrue(app.isKotlin && app.isCompose, "Compose app should be Kotlin + Compose")

            // SDK levels declared as `libs.versions.compileSdk.get().toInt()` must resolve through the catalog.
            assertEquals(37, app.compileSdk, "compileSdk from libs.versions")
            assertEquals(23, app.minSdk, "minSdk from libs.versions")
            assertEquals(36, app.targetSdk, "targetSdk from libs.versions")

            // The Compose BOM (bound to a local val, then applied) must be captured as a platform dependency.
            assertEquals(
                listOf("androidx.compose:compose-bom:2026.06.00"),
                app.platformDeps.map { it.coordinate },
                "the platform() BOM assigned to `val composeBom` and applied via implementation(composeBom)",
            )

            // Versionless compose libraries are captured (the BOM aligns their versions at resolve time).
            val coords = app.mavenDeps.map { it.coordinate }
            assertTrue("androidx.compose.material3:material3" in coords, "versionless material3 captured: $coords")
            assertTrue("androidx.compose.ui:ui" in coords, "versionless compose-ui captured: $coords")
            assertTrue("androidx.core:core-ktx:1.19.0" in coords, "versioned core-ktx captured: $coords")
            assertTrue("io.coil-kt:coil-compose:2.7.0" in coords, "coil captured: $coords")

            // debugImplementation carries the build-variant qualifier.
            val tooling = app.mavenDeps.single { it.coordinate == "androidx.compose.ui:ui-tooling" }
            assertEquals("debug", tooling.variant, "debugImplementation → variant=debug")
            assertEquals(DependencyScope.IMPLEMENTATION, tooling.scope)
        }
    }
}
