package dev.ide.lang.java

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.parse.JavaDiagnosticCodes
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.lang.resolve.ApiLevelContext
import dev.ide.lang.resolve.ApiLevel
import dev.ide.lang.resolve.PlatformApiLevels
import dev.ide.vfs.local.LocalFileSystem
import dev.ide.vfs.local.fileFor
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaApiLevelCheckTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private val fs = LocalFileSystem(Files.createTempDirectory("java-fs"))

    /** A platform small enough to read: what the table knows about the stub classes below. */
    private val levels = object : PlatformApiLevels {
        override val platformLevel = 36
        val classes = mapOf("android.app.NotificationChannel" to 26, "android.content.Context" to 1, "android.app.Activity" to 1)
        val methods = mapOf(
            "android.app.NotificationChannel#<init>(Ljava/lang/String;)V" to ApiLevel(26),
            "android.app.NotificationChannel#getId()Ljava/lang/String;" to ApiLevel(26),
            "android.content.Context#getColor(I)I" to ApiLevel(23),
            "android.content.Context#getColor(Ljava/lang/String;)I" to ApiLevel(30),
            "android.content.Context#getDrawable(I)Ljava/lang/Object;" to ApiLevel(21),
            "java.util.Objects#requireNonNullElse(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" to ApiLevel(30, desugared = true),
        )
        val fields = mapOf("android.os.Build.VERSION_CODES#O" to ApiLevel(26), "android.app.NotificationChannel#SHARED" to ApiLevel(26))
        val supers = mapOf("android.app.Activity" to "android.content.Context")

        private fun canon(n: String) = n.replace('/', '.').replace('$', '.')
        override fun classLevel(className: String) = classes[canon(className)]?.let { ApiLevel(it) }
        override fun methodLevel(ownerClass: String, name: String, descriptor: String?, arity: Int): ApiLevel? {
            var owner: String? = canon(ownerClass)
            while (owner != null) {
                val prefix = "$owner#$name("
                val hit = methods.filterKeys { it.startsWith(prefix) && (descriptor == null || it == "$owner#$name$descriptor") }
                if (hit.isNotEmpty()) return hit.values.minBy { it.since }
                owner = supers[owner]
            }
            return null
        }
        override fun fieldLevel(ownerClass: String, name: String) = fields["${canon(ownerClass)}#$name"]
    }

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        stub("android/os/Build.java", """
            package android.os;
            public class Build {
                public static class VERSION { public static final int SDK_INT = 0; }
                public static class VERSION_CODES { public static final int O = 26; public static final int N = 24; }
            }""")
        stub("android/content/Context.java", """
            package android.content;
            public abstract class Context {
                public final int getColor(int id) { return 0; }
                public final int getColor(String name) { return 0; }
                public Object getDrawable(int id) { return null; }
            }""")
        stub("android/app/Activity.java", "package android.app;\npublic class Activity extends android.content.Context {}")
        stub("android/app/NotificationChannel.java", """
            package android.app;
            public final class NotificationChannel {
                public static Object SHARED;
                public NotificationChannel(String id) {}
                public String getId() { return null; }
            }""")
        stub("androidx/annotation/RequiresApi.java", "package androidx.annotation;\npublic @interface RequiresApi { int value() default 1; int api() default 1; }")
        stub("android/annotation/TargetApi.java", "package android.annotation;\npublic @interface TargetApi { int value(); }")
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

    private fun newApi(body: String, minSdk: Int = 21, imports: String = ""): List<Diagnostic> {
        val src = """
            package com.foo;
            import android.app.*;
            import android.content.Context;
            import android.os.Build;
            import androidx.annotation.RequiresApi;
            $imports
            class Use {
            $body
            }
        """.trimIndent()
        val f = File(srcRoot, "com/foo/Use.java").apply { parentFile.mkdirs() }
        f.writeText(src)
        val parsed = JavaParsedFile(env.parse("com/foo/Use.java", src), fs.fileFor(f.toPath()), 1L, apiContext = { ApiLevelContext.android(minSdk, levels) })
        return parsed.diagnostics.filter { it.code == JavaDiagnosticCodes.NEW_API }
    }

    @Test
    fun unguardedConstructorAndCallAreReported() {
        val d = newApi("void m() { NotificationChannel c = new NotificationChannel(\"id\"); c.getId(); }")
        assertEquals(2, d.size, d.toString())
        assertTrue(d.all { it.severity == Severity.WARNING })
        assertEquals("Call requires API level 26 (current min is 21): `new android.app.NotificationChannel`", d[0].message)
        assertEquals("Call requires API level 26 (current min is 21): `android.app.NotificationChannel#getId`", d[1].message)
    }

    @Test
    fun minSdkAtTheLevelIsClean() {
        assertEquals(emptyList(), newApi("void m() { new NotificationChannel(\"id\"); }", minSdk = 26))
    }

    @Test
    fun sdkIntGuardCoversTheBranch() {
        assertEquals(emptyList(), newApi("""
            void m() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { new NotificationChannel("id"); }
                if (Build.VERSION.SDK_INT > 25 && true) new NotificationChannel("id");
                if (Build.VERSION.SDK_INT < 26) { } else { new NotificationChannel("id"); }
            }"""))
        assertEquals(1, newApi("void m() { if (Build.VERSION.SDK_INT >= 25) { new NotificationChannel(\"id\"); } }").size, "25 is not enough")
        assertEquals(1, newApi("void m() { if (Build.VERSION.SDK_INT >= 26) { } else { new NotificationChannel(\"id\"); } }").size, "the else branch is unguarded")
    }

    @Test
    fun earlyReturnGuardsTheRestOfTheBlock() {
        assertEquals(emptyList(), newApi("void m() { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return; new NotificationChannel(\"id\"); }"))
        assertEquals(1, newApi("void m() { new NotificationChannel(\"id\"); if (Build.VERSION.SDK_INT < 26) return; }").size)
    }

    @Test
    fun conditionalExpressionGuardsItsBranch() {
        assertEquals(emptyList(), newApi("int m(Context c) { return Build.VERSION.SDK_INT >= 23 ? c.getColor(1) : 0; }"))
    }

    @Test
    fun requiresApiAndTargetApiCoverTheirDeclaration() {
        assertEquals(emptyList(), newApi("@RequiresApi(26) void m() { new NotificationChannel(\"id\"); }"))
        assertEquals(emptyList(), newApi("@RequiresApi(api = Build.VERSION_CODES.O) void m() { new NotificationChannel(\"id\"); }"))
        assertEquals(emptyList(), newApi("@android.annotation.TargetApi(26) static class Inner { void m() { new NotificationChannel(\"id\"); } }"))
        assertEquals(1, newApi("@RequiresApi(24) void m() { new NotificationChannel(\"id\"); }").size)
    }

    @Test
    fun descriptorPicksTheOverloadAndInheritedMembersResolve() {
        val d = newApi("void m(Activity a) { a.getColor(1); a.getColor(\"x\"); a.getDrawable(1); }", minSdk = 23)
        assertEquals(1, d.size, d.toString())
        assertTrue(d[0].message.startsWith("Call requires API level 30"))
    }

    @Test
    fun constantsAreInlinedButOtherFieldsAreReported() {
        val d = newApi("void m() { int o = Build.VERSION_CODES.O; Object s = NotificationChannel.SHARED; }")
        assertEquals(1, d.size, d.toString())
        assertEquals("Field requires API level 26 (current min is 21): `android.app.NotificationChannel#SHARED`", d[0].message)
    }

    @Test
    fun backportedMethodIsNotReported() {
        assertEquals(emptyList(), newApi("Object m() { return java.util.Objects.requireNonNullElse(null, \"x\"); }"))
    }

    private class Fixes(val src: String, val target: dev.ide.analysis.AnalysisTarget, val fixes: List<dev.ide.analysis.QuickFix>)

    private fun fixesFor(body: String): Fixes {
        val src = "package com.foo;\nimport android.app.*;\nimport android.os.Build;\nimport androidx.annotation.RequiresApi;\nclass Use {\n$body\n}"
        val f = File(srcRoot, "com/foo/Use.java").apply { parentFile.mkdirs(); writeText(src) }
        val parsed = JavaParsedFile(env.parse("com/foo/Use.java", src), fs.fileFor(f.toPath()), 1L, apiContext = { ApiLevelContext.android(21, levels) })
        val d = parsed.diagnostics.first { it.code == JavaDiagnosticCodes.NEW_API }
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
        return Fixes(src, target, dev.ide.lang.java.analysis.NewApiFixProvider().fixes(diagnostic, target))
    }

    private fun Fixes.apply(fix: dev.ide.analysis.QuickFix): String {
        val fixTarget = target
        val edit = kotlinx.coroutines.runBlocking {
            fix.computeEdits(object : dev.ide.analysis.FixContext {
                override val target: dev.ide.analysis.AnalysisTarget = fixTarget
                override fun checkCanceled() {}
            })
        }
        return edit.edits.values.flatten().sortedByDescending { it.offset }
            .fold(src) { t, e -> t.substring(0, e.offset) + e.newText + t.substring(e.offset + e.oldLength) }
    }

    @Test
    fun fixesAnnotateTheMethodOrGuardTheStatement() {
        val f = fixesFor("    void m() {\n        new NotificationChannel(\"id\");\n    }")
        assertEquals(listOf("Add @RequiresApi(26) to 'm'", "Surround with if (Build.VERSION.SDK_INT >= 26)"), f.fixes.map { it.title })
        val annotated = f.apply(f.fixes[0])
        assertTrue("    @RequiresApi(26)\n    void m() {" in annotated, annotated)
        val guarded = f.apply(f.fixes[1])
        assertTrue("        if (Build.VERSION.SDK_INT >= 26) {\n            new NotificationChannel(\"id\");\n        }" in guarded, guarded)
    }

    @Test
    fun aLocalDeclarationIsNotWrapped() {
        val f = fixesFor("    void m() {\n        NotificationChannel c = new NotificationChannel(\"id\");\n    }")
        assertEquals(listOf("Add @RequiresApi(26) to 'm'"), f.fixes.map { it.title })
    }

    @Test
    fun noContextMeansNoCheck() {
        val src = "package com.foo;\nclass Plain { void m() { new android.app.NotificationChannel(\"id\"); } }"
        val f = File(srcRoot, "com/foo/Plain.java").apply { parentFile.mkdirs(); writeText(src) }
        val d = JavaParsedFile(env.parse("com/foo/Plain.java", src), fs.fileFor(f.toPath()), 1L).diagnostics
        assertTrue(d.none { it.code == JavaDiagnosticCodes.NEW_API })
    }
}
