package dev.ide.core

import dev.ide.model.DependencyScope
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
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
}
