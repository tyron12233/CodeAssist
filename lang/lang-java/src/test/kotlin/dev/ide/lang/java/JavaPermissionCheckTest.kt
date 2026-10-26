package dev.ide.lang.java

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.lang.resolve.PermissionContext
import dev.ide.lang.resolve.PermissionGuards
import dev.ide.lang.resolve.PermissionRequirement
import dev.ide.lang.resolve.PlatformPermissions
import dev.ide.vfs.local.LocalFileSystem
import dev.ide.vfs.local.fileFor
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Java missing-permission check, over stub platform sources and a stub permission table. */
class JavaPermissionCheckTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private val fs = LocalFileSystem(Files.createTempDirectory("java-fs"))

    private val table = object : PlatformPermissions {
        private val byKey = mapOf(
            "android.location.LocationManager#getLastKnownLocation#1" to PermissionRequirement(listOf(COARSE, FINE), allRequired = false),
            "android.media.AudioRecord#<init>#1" to PermissionRequirement(listOf(RECORD_AUDIO), allRequired = true),
        )
        override fun requirement(ownerClass: String, name: String, arity: Int) = byKey["$ownerClass#$name#$arity"]
    }

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        stub("android/location/LocationManager.java", """
            package android.location;
            public class LocationManager { public Object getLastKnownLocation(String provider) { return null; } }""")
        stub("android/media/AudioRecord.java", "package android.media;\npublic class AudioRecord { public AudioRecord(int source) {} }")
        stub("android/annotation/SuppressLint.java", "package android.annotation;\npublic @interface SuppressLint { String[] value(); }")
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    private fun stub(path: String, text: String) {
        File(srcRoot, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent())
    }

    private fun permissions(body: String, declared: Set<String> = emptySet()): List<Diagnostic> {
        val src = """
            package com.foo;
            import android.location.LocationManager;
            import android.media.AudioRecord;
            import android.annotation.SuppressLint;
            class Use {
            $body
            }
        """.trimIndent()
        val f = File(srcRoot, "com/foo/Use.java").apply { parentFile.mkdirs() }
        f.writeText(src)
        val ctx = PermissionContext(table, declared, minSdk = 21, targetSdk = 34)
        val parsed = JavaParsedFile(env.parse("com/foo/Use.java", src), fs.fileFor(f.toPath()), 1L, permissionContext = { ctx })
        return parsed.diagnostics.filter { it.code == PermissionGuards.MISSING_PERMISSION }
    }

    @Test
    fun aMissingPermissionAndAnUncheckedOneAreReported() {
        assertEquals(
            listOf("Missing permissions required by LocationManager.getLastKnownLocation: $COARSE or $FINE"),
            permissions("void m(LocationManager lm) { lm.getLastKnownLocation(\"gps\"); }").map { it.message },
        )
        assertEquals(
            listOf(PermissionGuards.UNCHECKED_MESSAGE + " (needs $COARSE)"),
            permissions("void m(LocationManager lm) { lm.getLastKnownLocation(\"gps\"); }", setOf(COARSE)).map { it.message },
        )
        assertEquals(
            listOf("Missing permissions required by new AudioRecord: $RECORD_AUDIO"),
            permissions("void m() { new AudioRecord(1); }").map { it.message },
        )
    }

    @Test
    fun aCheckACatchOrASuppressionHandlesIt() {
        assertEquals(emptyList(), permissions("""
            void m(LocationManager lm) {
                if (checkSelfPermission("$FINE") != 0) return;
                lm.getLastKnownLocation("gps");
            }
            int checkSelfPermission(String p) { return 0; }""", setOf(FINE)))
        assertEquals(emptyList(), permissions("""
            void m(LocationManager lm) {
                try { lm.getLastKnownLocation("gps"); } catch (IllegalStateException | SecurityException e) { }
            }""", setOf(FINE)))
        assertEquals(emptyList(), permissions("""
            @SuppressLint("MissingPermission")
            void m(LocationManager lm) { lm.getLastKnownLocation("gps"); }"""))
    }

    @Test
    fun theResultFollowsTheManifest() {
        // The same parse, asked again after the manifest gained the permission: the warning changes with it.
        val src = "package com.foo;\nimport android.media.AudioRecord;\nclass Use { void m() { new AudioRecord(1); } }"
        val f = File(srcRoot, "com/foo/Use.java").apply { parentFile.mkdirs(); writeText(src) }
        var declared = emptySet<String>()
        val parsed = JavaParsedFile(env.parse("com/foo/Use.java", src), fs.fileFor(f.toPath()), 1L,
            permissionContext = { PermissionContext(table, declared, minSdk = 21, targetSdk = 34) })
        assertEquals(1, parsed.diagnostics.count { it.code == PermissionGuards.MISSING_PERMISSION })
        declared = setOf(RECORD_AUDIO)
        assertEquals(listOf(PermissionGuards.UNCHECKED_MESSAGE + " (needs $RECORD_AUDIO)"),
            parsed.diagnostics.filter { it.code == PermissionGuards.MISSING_PERMISSION }.map { it.message })
    }

    @Test
    fun theFixChecksThePermissionAndReturns() {
        val src = "package com.foo;\nimport android.location.LocationManager;\nclass Use {\n    void m(LocationManager lm) {\n        lm.getLastKnownLocation(\"gps\");\n    }\n}"
        val f = File(srcRoot, "com/foo/Use.java").apply { parentFile.mkdirs(); writeText(src) }
        val parsed = JavaParsedFile(env.parse("com/foo/Use.java", src), fs.fileFor(f.toPath()), 1L,
            permissionContext = { PermissionContext(table, setOf(FINE), minSdk = 21, targetSdk = 34) })
        val d = parsed.diagnostics.single { it.code == PermissionGuards.MISSING_PERMISSION }
        val target = object : dev.ide.analysis.AnalysisTarget {
            override val file = parsed.file
            override val parsed = parsed
            override val documentVersion = 1L
            override val resolver: dev.ide.lang.SourceAnalyzer get() = throw UnsupportedOperationException()
            override val index: dev.ide.index.IndexService get() = throw UnsupportedOperationException()
            override val module: dev.ide.model.Module get() = throw UnsupportedOperationException()
            override fun checkCanceled() {}
        }
        val diagnostic = dev.ide.analysis.Diagnostic(d.range, d.severity, d.message, dev.ide.analysis.DiagnosticSource.Compiler, d.code)
        val fix = dev.ide.lang.java.analysis.PermissionCheckFixProvider().fixes(diagnostic, target).single()
        assertEquals("Add permission check", fix.title)
        val edit = kotlinx.coroutines.runBlocking {
            fix.computeEdits(object : dev.ide.analysis.FixContext {
                override val target: dev.ide.analysis.AnalysisTarget = target
                override fun checkCanceled() {}
            })
        }
        val fixed = edit.edits.values.flatten().sortedByDescending { it.offset }
            .fold(src) { t, e -> t.substring(0, e.offset) + e.newText + t.substring(e.offset + e.oldLength) }
        kotlin.test.assertTrue(
            "        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {\n" in fixed &&
                "            return;\n        }\n        lm.getLastKnownLocation" in fixed &&
                "import android.content.pm.PackageManager;\nimport android.Manifest;" in fixed,
            fixed,
        )
    }

    private companion object {
        const val FINE = "android.permission.ACCESS_FINE_LOCATION"
        const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    }
}
