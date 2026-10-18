package dev.ide.core

import dev.ide.core.gradle.GradleImport
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A project whose Gradle plugins generate code CodeAssist does not run imports and builds while nothing uses
 * that code, then fails on the first query or table the user adds. The import says so up front. Shapes from
 * the Compose Multiplatform Wizard (terrakok) with every library ticked.
 */
class GradleCodegenNotesTest {

    private fun Path.w(rel: String, text: String) {
        val f = resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, text.trimIndent())
    }

    @Test
    fun namesTheCodeGeneratorsItDoesNotRun() {
        withTempDir("codegen-notes") { root ->
            root.w("settings.gradle.kts", """
                rootProject.name = "EverythingApp"
                include(":sharedUI")
            """)
            root.w("gradle/libs.versions.toml", """
                [versions]
                apollo = "4.3.3"
                kotlinInject = "0.8.0"
                room = "2.8.4"
                [libraries]
                kotlinInjectKsp = { module = "me.tatarka.inject:kotlin-inject-compiler-ksp", version.ref = "kotlinInject" }
                room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
                [plugins]
                apollo = { id = "com.apollographql.apollo", version.ref = "apollo" }
                sqlDelight = { id = "app.cash.sqldelight", version = "2.1.0" }
                ksp = { id = "com.google.devtools.ksp", version = "2.3.10" }
            """)
            root.w("sharedUI/build.gradle.kts", """
                plugins {
                    alias(libs.plugins.apollo)
                    alias(libs.plugins.sqlDelight)
                    alias(libs.plugins.ksp)
                }
                dependencies {
                    with(libs.kotlinInjectKsp) {
                        add("kspAndroid", this)
                        add("kspJvm", this)
                    }
                    with(libs.room.compiler) {
                        add("kspAndroid", this)
                    }
                }
            """)
            val notes = assertNotNull(GradleImport.parse(root)).report.notes
            assertTrue(notes.any { "Apollo GraphQL" in it && it.startsWith("sharedUI:") }, "$notes")
            assertTrue(notes.any { "SQLDelight" in it }, "$notes")
            assertTrue(notes.any { "me.tatarka.inject:kotlin-inject-compiler-ksp" in it }, "$notes")
            assertTrue(notes.none { "room-compiler" in it }, "Room's processor is bundled: $notes")
        }
    }
}
