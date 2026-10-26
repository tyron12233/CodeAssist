package dev.ide.core

import dev.ide.android.support.tools.AndroidSdk
import dev.ide.lang.resolve.PermissionGuards
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The missing-permission check end to end: the demo Android app's real manifest, the bundled permission table,
 * both editors. A location call is reported missing until the manifest declares the permission, then reported
 * unchecked until the function checks it. Needs an installed SDK (the platform the call resolves against).
 */
class MissingPermissionE2ETest {

    private val root = createTempDirectory("missing-permission-e2e")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun aLocationCallFollowsTheManifest() {
        assumeTrue(AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }?.isComplete() == true, "Android SDK not installed; skipping")
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        val backend = IdeServicesBackend(s)
        val kotlin = "package com.example.app\n\nimport android.location.LocationManager\n\n" +
            "fun where(lm: LocationManager) = lm.getLastKnownLocation(\"gps\")\n"
        val java = "package com.example.app;\n\nimport android.location.LocationManager;\n\n" +
            "class Where {\n    Object where(LocationManager lm) { return lm.getLastKnownLocation(\"gps\"); }\n}\n"
        val kotlinFile = write("Where.kt", kotlin)
        val javaFile = write("Where.java", java)
        s.modules()
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && !s.indexService.status.ready) Thread.sleep(50)

        fun messages(): List<String> = runBlocking {
            backend.editor.analyze(kotlinFile.toString(), kotlin) + backend.editor.analyze(javaFile.toString(), java)
        }.map { it.message }.filter { it.startsWith("Missing permissions") || it.startsWith(PermissionGuards.UNCHECKED_MESSAGE) }

        val missing = "Missing permissions required by LocationManager.getLastKnownLocation: " +
            "android.permission.ACCESS_COARSE_LOCATION or android.permission.ACCESS_FINE_LOCATION"
        assertEquals(listOf(missing, missing), messages())

        val manifest = root.resolve("app/src/main/AndroidManifest.xml")
        manifest.writeText(manifest.readText().replace("<application",
            "<uses-permission android:name=\"android.permission.ACCESS_FINE_LOCATION\" />\n    <application"))
        Thread.sleep(2_100) // past the module's permission-context reuse window
        val unchecked = PermissionGuards.UNCHECKED_MESSAGE + " (needs android.permission.ACCESS_FINE_LOCATION)"
        assertEquals(listOf(unchecked, unchecked), messages())
    }

    private fun write(name: String, text: String): Path {
        val file = root.resolve("app/src/main/java/com/example/app/$name")
        Files.createDirectories(file.parent)
        Files.write(file, text.toByteArray())
        return file
    }
}
