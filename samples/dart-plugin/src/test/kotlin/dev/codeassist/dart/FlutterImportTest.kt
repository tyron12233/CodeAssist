package dev.codeassist.dart

import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.SyncReason
import dev.ide.model.sync.SyncRequest
import dev.ide.platform.ProgressReporter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A project `flutter create` made (Flutter 3.47.9's Kotlin app template, rendered) opens with its Android app. */
class FlutterImportTest {

    @TempDir
    lateinit var dir: Path

    private fun resource(name: String): String =
        javaClass.classLoader.getResourceAsStream("flutter-create/$name")!!.readBytes().toString(Charsets.UTF_8)

    private val noProgress = object : ProgressReporter {
        override fun report(fraction: Double, message: String?) {}
        override fun checkCanceled() {}
        override val isCanceled = false
    }

    private fun write(rel: String, text: String) =
        dir.resolve(rel).also { Files.createDirectories(it.parent); Files.write(it, text.toByteArray()) }

    @Test
    fun theTemplatesBuildFileReads() {
        val build = FlutterPlugins.parseAppBuild(resource("app.build.gradle.kts.txt"))
        assertEquals("com.example.real_app", build.namespace)
        assertEquals("com.example.real_app", build.applicationId)
        // `flutter.minSdkVersion` and the like are the Flutter Gradle plugin's: left for the importer to fill.
        assertNull(build.minSdk)
        assertNull(build.compileSdk)
        assertNull(build.targetSdk)
        assertNull(build.versionCode)
        assertNull(build.versionName)
        assertTrue(build.dependencies.isEmpty())
    }

    @Test
    fun aFlutterCreateProjectImportsWithItsAndroidApp() {
        write("pubspec.yaml", """
            name: real_app
            description: "A new Flutter project."
            publish_to: 'none'
            version: 1.2.0+7
            environment:
              sdk: ^3.9.0
            dependencies:
              flutter:
                sdk: flutter
            flutter:
              uses-material-design: true
        """.trimIndent())
        write("lib/main.dart", "void main() {}")
        write("android/app/build.gradle.kts", resource("app.build.gradle.kts.txt"))
        write("android/app/src/main/AndroidManifest.xml", resource("AndroidManifest.xml.txt"))
        write("android/app/src/main/kotlin/com/example/real_app/MainActivity.kt", "package com.example.real_app\n")
        // No package config: pub cannot run here, so the import goes on without plugins and says why.

        val outcome = runBlocking { PubImporter.resolve(SyncRequest(dir, noProgress, SyncReason.IMPORT)) }
        val model = outcome.model!!

        assertEquals(listOf("real_app", "app"), model.modules.map { it.name })
        val app = model.modules.single { it.name == "app" }
        assertEquals("android/app", app.dirRelPath)
        assertEquals("android-app", app.typeId)
        val android = app.facets.single { it.table == "android" }.values
        assertEquals("com.example.real_app", android["namespace"])
        assertEquals("com.example.real_app", android["applicationId"])
        assertEquals(36, android["compileSdk"])
        assertEquals(24, android["minSdk"])
        assertEquals(36, android["targetSdk"])
        assertEquals(7, android["versionCode"], "the pubspec's build number, as flutter.versionCode")
        assertEquals("1.2.0", android["versionName"])
        // The manifest names `${'$'}{applicationName}`, which only the Flutter Gradle plugin fills in.
        assertEquals(listOf("applicationName=android.app.Application"), android["manifestPlaceholders"])
        assertTrue(app.facets.any { it.table == "flutter" })
        val embeddings = app.dependencies.filterIsInstance<ExternalLibrary>().associate { it.variant to it.coordinate }
        assertEquals(FlutterSdk.EMBEDDING_DEBUG, embeddings["debug"])
        assertEquals(FlutterSdk.EMBEDDING_RELEASE, embeddings["release"])
        assertTrue(model.repositories.any { it.url == FlutterSdk.MAVEN_REPOSITORY })
        assertTrue(Files.isRegularFile(dir.resolve("android/app/src/main/java/io/flutter/plugins/GeneratedPluginRegistrant.java")))
    }

    @Test
    fun theBuildFilesSettingsApplyOnlyWhenItChanges() {
        write("pubspec.yaml", "name: real_app\nversion: 1.0.0+1\ndependencies:\n  flutter:\n    sdk: flutter\n")
        write("android/app/build.gradle.kts", resource("app.build.gradle.kts.txt"))
        write("android/app/src/main/AndroidManifest.xml", resource("AndroidManifest.xml.txt"))
        fun androidFacet() = runBlocking { PubImporter.resolve(SyncRequest(dir, noProgress, SyncReason.IMPORT)) }
            .model!!.modules.single { it.name == "app" }.facets.firstOrNull { it.table == "android" }

        assertTrue(androidFacet() != null, "the first sync takes the settings from the build file")
        // Nothing changed in the build file: a later sync (say, after pub get) leaves Module Settings edits alone.
        assertNull(androidFacet())
        write("android/app/build.gradle.kts", resource("app.build.gradle.kts.txt").replace("com.example.real_app\"", "com.example.renamed\""))
        assertEquals("com.example.renamed", androidFacet()!!.values["applicationId"], "an edited build file wins again")
    }

    @Test
    fun theVersionComesFromThePubspec() {
        assertEquals(7, PubImporter.versionCode("1.2.0+7"))
        assertEquals(1, PubImporter.versionCode("1.2.0"))
        assertEquals(1, PubImporter.versionCode(null))
        assertEquals("1.2.0", PubImporter.versionName("1.2.0+7"))
        assertEquals("1.0.0", PubImporter.versionName(null))
    }

    @Test
    fun aMissingNativeBuildSaysWhatToInstall() {
        val message = FlutterApkTask.noNativeBuild(listOf("jni"))
        assertTrue("jni has C code" in message, message)
        assertTrue("NDK (C/C++)" in message)
    }
}
