package dev.ide.core

import dev.ide.model.DependencyScope
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GradleImportTest {

    /** Lay down a two-module legacy Gradle project (android `app` -> java `core`) like older versions produced. */
    private fun writeLegacyGradleProject(dir: Path) {
        fun w(rel: String, text: String) {
            val f = dir.resolve(rel)
            Files.createDirectories(f.parent)
            f.writeText(text.trimIndent())
        }
        w("settings.gradle", """
            rootProject.name = 'MyApp'
            include ':app', ':core'
        """)
        w("build.gradle", "// top-level")
        w("app/build.gradle", """
            apply plugin: 'com.android.application'
            android {
                namespace "com.example.myapp"
                compileSdkVersion 33
                defaultConfig {
                    minSdkVersion 24
                    targetSdkVersion 33
                }
            }
            dependencies {
                implementation 'androidx.appcompat:appcompat:1.6.1'
                api project(':core')
                testImplementation 'junit:junit:4.13.2'
            }
        """)
        w("app/src/main/AndroidManifest.xml", """<manifest package="com.example.myapp"/>""")
        w("app/src/main/java/com/example/myapp/MainActivity.java", "package com.example.myapp; class MainActivity {}")
        w("core/build.gradle", """
            apply plugin: 'java-library'
            dependencies {
                implementation 'com.google.guava:guava:31.1-jre'
            }
        """)
        w("core/src/main/java/com/example/core/Core.java", "package com.example.core; public class Core {}")
    }

    /** The tolerant reader extracts modules, types, the android SDK/namespace, and dependency coordinates+scopes. */
    @Test
    fun parsesModulesTypesAndDependencies() {
        val tmp = Files.createTempDirectory("gradle-parse")
        try {
            val proj = tmp.resolve("MyApp")
            writeLegacyGradleProject(proj)

            val spec = GradleImport.parse(proj)
            assertNotNull(spec)
            assertEquals("MyApp", spec.name)
            assertEquals(setOf("app", "core"), spec.modules.map { it.name }.toSet())

            val app = spec.modules.first { it.name == "app" }
            assertEquals(GradleImport.Kind.ANDROID_APP, app.kind)
            assertEquals("com.example.myapp", app.namespace)
            assertEquals(33, app.compileSdk)
            assertEquals(24, app.minSdk)
            assertEquals(33, app.targetSdk)
            assertEquals(listOf("core"), app.moduleDeps.map { it.name })
            assertEquals(DependencyScope.API, app.moduleDeps.first().scope)
            // appcompat (implementation) + junit (testImplementation) are kept; the project(:core) dep is not a maven coord.
            assertEquals(
                setOf("androidx.appcompat:appcompat:1.6.1", "junit:junit:4.13.2"),
                app.mavenDeps.map { it.coordinate }.toSet(),
            )

            val core = spec.modules.first { it.name == "core" }
            assertEquals(GradleImport.Kind.JAVA, core.kind)
            assertEquals(listOf("com.google.guava:guava:31.1-jre"), core.mavenDeps.map { it.coordinate })
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** End to end: a legacy Gradle project in a legacy data dir is imported into the picker in compatibility mode. */
    @Test
    fun importsGradleProjectInCompatibilityMode() {
        val tmp = Files.createTempDirectory("gradle-import")
        try {
            val legacyHome = tmp.resolve("legacy")
            writeLegacyGradleProject(legacyHome.resolve("MyApp"))

            val manager = ProjectManager.desktop(
                tmp.resolve("projects"),
                legacyDataDirs = listOf(legacyHome),
            )
            assertTrue(manager.isEmpty(), "fresh root starts empty")

            assertEquals(1, manager.importLegacyProjects(), "one Gradle project recovered")

            val listed = manager.list()
            assertEquals(1, listed.size)
            assertEquals("MyApp", listed.first().name)
            assertEquals(2, listed.first().moduleCount, "app + core modules")
            assertTrue(listed.first().compatibility, "flagged as compatibility mode")

            // It opens, and the imported modules + sources are present.
            manager.open(listed.first().rootPath).use { ide ->
                assertEquals(setOf("app", "core"), ide.moduleNames().toSet())
            }
            val dest = Path.of(listed.first().rootPath)
            assertTrue(
                Files.exists(dest.resolve("app/src/main/java/com/example/myapp/MainActivity.java")),
                "sources were copied into the imported project",
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** A modern AGP project: Kotlin DSL, a version catalog, a Compose BOM platform, variables, build types + flavors. */
    private fun writeModernGradleProject(dir: Path) {
        fun w(rel: String, text: String) {
            val f = dir.resolve(rel)
            Files.createDirectories(f.parent)
            f.writeText(text.trimIndent())
        }
        w("settings.gradle.kts", """
            rootProject.name = "Modern"
            include(":app", ":core")
        """)
        w("gradle.properties", "leakVersion=2.12")
        w("gradle/libs.versions.toml", """
            [versions]
            coreKtx = "1.12.0"
            kotlin = "1.9.22"
            junit = "4.13.2"

            [libraries]
            androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
            compose-ui = { group = "androidx.compose.ui", name = "ui", version = "1.6.1" }
            compose-material3 = "androidx.compose.material3:material3:1.2.0"
            junit = { module = "junit:junit", version.ref = "junit" }

            [bundles]
            compose = ["compose-ui", "compose-material3"]

            [plugins]
            kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
        """)
        w("app/build.gradle.kts", """
            plugins {
                id("com.android.application")
                alias(libs.plugins.kotlin.android)
            }
            android {
                namespace = "com.example.modern"
                compileSdk = 34
                defaultConfig {
                    minSdk = 24
                    targetSdk = 34
                }
                flavorDimensions += "tier"
                productFlavors {
                    create("free") { dimension = "tier" }
                    create("paid") { dimension = "tier" }
                }
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        isShrinkResources = true
                    }
                    debug {
                    }
                }
                buildFeatures {
                    compose = true
                }
            }
            dependencies {
                implementation(platform("androidx.compose:compose-bom:2024.02.00"))
                implementation(libs.androidx.core.ktx)
                implementation(libs.bundles.compose)
                debugImplementation("com.squareup.leakcanary:leakcanary-android:${'$'}leakVersion")
                implementation(project(":core"))
                testImplementation(libs.junit)
            }
        """)
        w("app/src/main/AndroidManifest.xml", """<manifest package="com.example.modern"/>""")
        w("core/build.gradle", """
            plugins {
                id 'java-library'
            }
            ext {
                guavaVersion = '32.1.3-jre'
            }
            dependencies {
                // a commented-out line must be ignored
                // implementation 'should.not:appear:1.0'
                implementation "com.google.guava:guava:${'$'}guavaVersion"
            }
        """)
        w("core/src/main/java/com/example/core/Core.java", "package com.example.core; public class Core {}")
    }

    /** The tolerant reader resolves version-catalog accessors, `$var`/property interpolation, and BOM platforms. */
    @Test
    fun parsesCatalogVariablesAndPlatforms() {
        val tmp = Files.createTempDirectory("gradle-modern")
        try {
            val proj = tmp.resolve("Modern")
            writeModernGradleProject(proj)

            val spec = GradleImport.parse(proj)
            assertNotNull(spec)
            assertEquals("Modern", spec.name)

            val app = spec.modules.first { it.name == "app" }
            assertEquals(GradleImport.Kind.ANDROID_APP, app.kind)
            assertTrue(app.isKotlin, "kotlin.android plugin resolved through the catalog alias")
            assertTrue(app.isCompose, "buildFeatures { compose = true }")
            assertEquals("com.example.modern", app.namespace)
            assertEquals(34, app.compileSdk)
            assertEquals(24, app.minSdk)

            val coords = app.mavenDeps.map { it.coordinate }.toSet()
            assertContains(coords, "androidx.core:core-ktx:1.12.0")          // library { module, version.ref }
            assertContains(coords, "androidx.compose.ui:ui:1.6.1")           // bundle → { group, name, version }
            assertContains(coords, "androidx.compose.material3:material3:1.2.0") // bundle → shorthand string
            assertContains(coords, "junit:junit:4.13.2")                     // testImplementation via catalog
            assertContains(coords, "com.squareup.leakcanary:leakcanary-android:2.12") // $var from gradle.properties

            // The Compose BOM is a platform, not a normal library.
            assertEquals(listOf("androidx.compose:compose-bom:2024.02.00"), app.platformDeps.map { it.coordinate })
            assertFalse(coords.any { it.startsWith("androidx.compose:compose-bom") }, "BOM isn't a library dep")

            // junit is test-scoped; the debug-only dep carries its variant qualifier.
            assertEquals(DependencyScope.TEST_IMPLEMENTATION, app.mavenDeps.first { it.coordinate.startsWith("junit:") }.scope)
            assertEquals("debug", app.mavenDeps.first { it.coordinate.contains("leakcanary") }.variant)

            // Build types + flavors.
            val release = app.buildTypes.first { it.name == "release" }
            assertTrue(release.minifyEnabled && release.shrinkResources)
            assertTrue(app.buildTypes.any { it.name == "debug" })
            assertEquals(listOf("tier"), app.flavorDimensions)
            assertEquals(setOf("free", "paid"), app.productFlavors.map { it.name }.toSet())
            assertTrue(app.productFlavors.all { it.dimension == "tier" })

            assertEquals(listOf("core"), app.moduleDeps.map { it.name })

            // core: Groovy, ext-var version, and the commented-out dependency must be ignored.
            val core = spec.modules.first { it.name == "core" }
            assertEquals(GradleImport.Kind.JAVA, core.kind)
            assertEquals(listOf("com.google.guava:guava:32.1.3-jre"), core.mavenDeps.map { it.coordinate })
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** An unresolved catalog/variable reference is reported in the sync notes rather than silently dropped. */
    @Test
    fun reportsUnresolvedReferences() {
        val tmp = Files.createTempDirectory("gradle-report")
        try {
            val proj = tmp.resolve("Rep")
            Files.createDirectories(proj)
            proj.resolve("settings.gradle").writeText("rootProject.name = 'Rep'\ninclude ':app'")
            val app = proj.resolve("app")
            Files.createDirectories(app.resolve("src/main"))
            app.resolve("build.gradle").writeText(
                """
                apply plugin: 'java-library'
                dependencies {
                    implementation libs.does.not.exist
                    implementation "com.example:thing:${'$'}undefinedVersion"
                }
                """.trimIndent(),
            )

            val spec = GradleImport.parse(proj)
            assertNotNull(spec)
            assertTrue(spec.modules.first().mavenDeps.isEmpty(), "neither unresolved dep is added")
            assertTrue(spec.report.notes.any { "libs.does.not.exist" in it }, "catalog miss noted")
            assertTrue(spec.report.notes.any { "undefinedVersion" in it }, "variable miss noted")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** Re-sync re-reads the (edited) scripts into an open project: a new module + a new dependency appear. */
    @Test
    fun resyncsFromEditedScripts() {
        val tmp = Files.createTempDirectory("gradle-resync")
        try {
            val legacyHome = tmp.resolve("legacy")
            writeLegacyGradleProject(legacyHome.resolve("MyApp"))
            val manager = ProjectManager.desktop(tmp.resolve("projects"), legacyDataDirs = listOf(legacyHome))
            assertEquals(1, manager.importLegacyProjects())
            val summary = manager.list().first()
            val dest = Path.of(summary.rootPath)

            manager.open(summary.rootPath).use { ide ->
                assertEquals(setOf("app", "core"), ide.moduleNames().toSet())
                // Add a brand-new module and a new dependency to the (copied) scripts, then sync.
                dest.resolve("settings.gradle").writeText("rootProject.name = 'MyApp'\ninclude ':app', ':core', ':lib'")
                val lib = dest.resolve("lib")
                Files.createDirectories(lib.resolve("src/main/java/com/example/lib"))
                lib.resolve("build.gradle").writeText("apply plugin: 'java-library'\n")
                lib.resolve("src/main/java/com/example/lib/Lib.java").writeText("package com.example.lib; public class Lib {}")

                val outcome = ide.syncGradleFromScripts()
                assertTrue(outcome.ok, outcome.message)
                assertContains(ide.moduleNames().toSet(), "lib")
                assertTrue(ide.isCompatibilityMode(), "still flagged as compatibility mode after a sync")
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
