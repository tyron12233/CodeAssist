package dev.codeassist.dart

import dev.ide.model.Workspace
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.TemplateArgs
import dev.ide.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime

class FlutterApkTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun theFlutterTableRoundTrips() {
        assertEquals(FlutterFacet("../.."), FlutterFacetCodec.decode(emptyMap()))
        assertEquals(FlutterFacet("../../app"), FlutterFacetCodec.decode(FlutterFacetCodec.encode(FlutterFacet("../../app"))))
        // A blank source would resolve to the module itself; the default is what was meant.
        assertEquals(FlutterFacet(), FlutterFacetCodec.decode(mapOf("source" to " ")))
    }

    @Test
    fun theEngineDirectoryNamesItsAbi() {
        // An installed app's extracted libraries are under the instruction set's name, an APK's under the ABI's.
        assertEquals("arm64-v8a", FlutterApkTask.abiOf(Paths.get("/data/app/x/lib/arm64")))
        assertEquals("arm64-v8a", FlutterApkTask.abiOf(Paths.get("lib/arm64-v8a")))
        assertEquals("armeabi-v7a", FlutterApkTask.abiOf(Paths.get("/data/app/x/lib/arm")))
        assertEquals("x86_64", FlutterApkTask.abiOf(Paths.get("/data/app/x/lib/x86_64")))
        assertNull(FlutterApkTask.abiOf(Paths.get("/data/app/x/lib/mips")))
        assertNull(FlutterApkTask.abiOf(null))
    }

    @Test
    fun onlyReleaseVariantsAreRelease() {
        assertTrue(FlutterBuildPlugin.isRelease("release"))
        assertTrue(FlutterBuildPlugin.isRelease("freeRelease"))
        assertFalse(FlutterBuildPlugin.isRelease("debug"))
        assertFalse(FlutterBuildPlugin.isRelease("freeDebug"))
    }

    @Test
    fun anUnchangedFileIsNotCopiedAgain() {
        val from = dir.resolve("app.dill").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val to = dir.resolve("out/flutter_assets/kernel_blob.bin")
        FlutterApkTask.copyIfChanged(from, to)
        assertEquals(Files.getLastModifiedTime(from), Files.getLastModifiedTime(to))

        // Same size and time: left alone, so the APK's inputs do not change for nothing.
        Files.write(to, byteArrayOf(9, 9, 9))
        Files.setLastModifiedTime(to, Files.getLastModifiedTime(from))
        FlutterApkTask.copyIfChanged(from, to)
        assertEquals(listOf<Byte>(9, 9, 9), Files.readAllBytes(to).toList())

        // A new compile (a new time) is copied.
        Files.setLastModifiedTime(from, FileTime.fromMillis(Files.getLastModifiedTime(from).toMillis() + 5_000))
        FlutterApkTask.copyIfChanged(from, to)
        assertEquals(listOf<Byte>(1, 2, 3), Files.readAllBytes(to).toList())
    }

    @Test
    fun identicalContentKeepsItsTime() {
        val to = dir.resolve("FontManifest.json")
        FlutterApkTask.writeIfChanged("[]".byteInputStream(), to)
        val old = FileTime.fromMillis(1_000_000)
        Files.setLastModifiedTime(to, old)
        FlutterApkTask.writeIfChanged("[]".byteInputStream(), to)
        assertEquals(old, Files.getLastModifiedTime(to))
        FlutterApkTask.writeIfChanged("[{}]".byteInputStream(), to)
        assertEquals("[{}]", String(Files.readAllBytes(to)))
    }

    @Test
    fun theAndroidSideIsWhatAFlutterActivityAppNeeds() {
        val files = LinkedHashMap<String, String>()
        FlutterAndroidFiles.write(RecordingScaffold(files), "android/app", "com.example.counter", "Tom & Jerry's <App>")

        val main = "android/app/src/main"
        val manifest = files.getValue("$main/AndroidManifest.xml")
        assertTrue("android:label=\"Tom &amp; Jerry's &lt;App>\"" in manifest, manifest)
        assertTrue("android:name=\".MainActivity\"" in manifest)
        assertTrue("android.intent.category.LAUNCHER" in manifest)
        assertTrue("io.flutter.embedding.android.NormalTheme" in manifest)
        assertTrue("android.permission.INTERNET" in manifest)
        assertTrue(files.getValue("$main/java/com/example/counter/MainActivity.java").let {
            it.startsWith("package com.example.counter;") && "extends FlutterActivity" in it
        })
        assertTrue("registerWith(@NonNull FlutterEngine" in files.getValue("$main/java/io/flutter/plugins/GeneratedPluginRegistrant.java"))
        // Every resource the manifest and themes name exists.
        for (path in listOf(
            "res/values/styles.xml", "res/values-night/styles.xml", "res/values/colors.xml",
            "res/drawable/launch_background.xml", "res/drawable/ic_launcher_foreground.xml",
            "res/mipmap-anydpi-v26/ic_launcher.xml",
        )) assertTrue("$main/$path" in files, path)
        assertTrue(files.values.none { "\${" in it }, "a template placeholder was left unfilled")
    }

    @Test
    fun theTemplateDeclaresOneEmbeddingPerBuildType() {
        val deps = FlutterAppTemplate().dependencies(TemplateArgs(emptyMap()))
        assertTrue(deps.all { it.module == FlutterAppTemplate.ANDROID_MODULE })
        assertEquals(
            setOf(
                "io.flutter:flutter_embedding_debug:1.0.0-${FlutterSdk.ENGINE}",
                "io.flutter:flutter_embedding_release:1.0.0-${FlutterSdk.ENGINE}",
            ),
            deps.map { it.coordinate }.toSet(),
        )
        assertEquals(
            mapOf("debug" to FlutterSdk.EMBEDDING_DEBUG, "release" to FlutterSdk.EMBEDDING_RELEASE),
            FlutterAppTemplate.EMBEDDINGS.toMap(),
        )
    }

    @Test
    fun theReleaseKernelIsCompiledAheadOfTimeAgainstTheProductPlatform() {
        val command = FlutterApkTask.aotKernelCommand(
            Paths.get("/lib/libdartaotruntime.so"), Paths.get("/lib/libfrontend_server.so"),
            Paths.get("/sdk/flutter_patched_sdk_product"), Paths.get("/app/.dart_tool/package_config.json"),
            Paths.get("/app/build/app.dill"), "package:app/main.dart",
        )
        assertEquals("/lib/libdartaotruntime.so", command.first())
        assertEquals("package:app/main.dart", command.last())
        // The trailing slash matters: the compiler appends file names to the sdk root as given.
        assertEquals("/sdk/flutter_patched_sdk_product/", command[command.indexOf("--sdk-root") + 1])
        for (flag in listOf("--target=flutter", "--aot", "--tfa", "-Ddart.vm.product=true", "--output-dill=/app/build/app.dill")) {
            assertTrue(flag in command, flag)
        }
        assertFalse("--incremental" in command)
        assertFalse("--enable-asserts" in command)
    }

    @Test
    fun genSnapshotWritesAStrippedElfLibrary() {
        val arm64 = FlutterApkTask.genSnapshotCommand(Paths.get("/lib/libgen_snapshot.so"), "arm64-v8a", Paths.get("app.dill"), Paths.get("libapp.so"))
        assertEquals(
            listOf("/lib/libgen_snapshot.so", "--deterministic", "--snapshot_kind=app-aot-elf", "--elf=libapp.so", "--strip", "app.dill"),
            arm64,
        )
        val arm = FlutterApkTask.genSnapshotCommand(Paths.get("gs"), "armeabi-v7a", Paths.get("app.dill"), Paths.get("libapp.so"))
        assertTrue("--no-use-integer-division" in arm)
        assertEquals("app.dill", arm.last())
    }

    @Test
    fun theCompilersResultLineCarriesItsErrorCount() {
        assertEquals(0, FlutterApkTask.compileErrors("9b6763f8-a07f-47ef-8219-a340a2e2c7c1 /data/x/app.dill 0"))
        assertEquals(3, FlutterApkTask.compileErrors("9b6763f8 /data/x/app.dill 3"))
        assertNull(FlutterApkTask.compileErrors("lib/main.dart:3:5: Error: Expected ';' after this."))
        assertNull(FlutterApkTask.compileErrors("+file:///data/x/lib/main.dart"))
    }

    @Test
    fun theEngineRevisionMatchesThePluginsOwnBuild() {
        // The plugin's player is built against the embedding of this revision; an app has to get the same one.
        val build = String(Files.readAllBytes(Paths.get("build.gradle.kts")))
        assertTrue("\"${FlutterSdk.ENGINE}\"" in build, "build.gradle.kts pins another engine than FlutterSdk.ENGINE")
    }

    /** Records [writeText] calls; the model surface is not used by [FlutterAndroidFiles]. */
    private class RecordingScaffold(private val files: MutableMap<String, String>) : ProjectScaffold {
        override val workspace: Workspace get() = error("not used")
        override val rootDir: VirtualFile get() = error("not used")
        override val languageLevel get() = error("not used")
        override fun moduleType(id: String) = error("not used")
        override fun writeText(relPath: String, content: String) { files[relPath] = content }
        override fun writeBytes(relPath: String, bytes: ByteArray) { files[relPath] = String(bytes) }
    }
}
