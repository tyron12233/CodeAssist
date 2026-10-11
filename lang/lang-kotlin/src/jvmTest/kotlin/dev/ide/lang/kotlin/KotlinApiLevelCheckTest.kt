package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.resolve.ApiLevelContext
import dev.ide.lang.resolve.ApiLevel
import dev.ide.lang.resolve.PlatformApiLevels
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin minSdk check: platform calls, constructor calls and Java-accessor properties newer than the
 * module's minSdk are reported unless an `SDK_INT` guard or `@RequiresApi` covers them. The platform is a
 * small bytecode jar standing in for `android.jar`, and the table a stub [PlatformApiLevels].
 */
class KotlinApiLevelCheckTest {

    private fun newApi(body: String, minSdk: Int = 21): List<Diagnostic> {
        minSdkFor = minSdk
        val code = """
            package demo
            import android.app.NotificationChannel
            import android.view.View
            import android.os.Build
            import androidx.annotation.RequiresApi
            $body
        """.trimIndent()
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use${counter.incrementAndGet()}.kt")))
        val all = runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
        return all.filter { it.code == KotlinDiagnosticCodes.NEW_API }
    }

    @Test
    fun unguardedConstructorCallAndAccessorAreReported() {
        val d = newApi("fun m(v: View) { val c = NotificationChannel(\"id\"); c.getId(); val s = v.stateDescription }")
        assertEquals(3, d.size, d.toString())
        assertTrue(d.all { it.severity == Severity.WARNING })
        assertEquals("Call requires API level 26 (current min is 21): `new android.app.NotificationChannel`", d[0].message)
        assertEquals("Call requires API level 26 (current min is 21): `android.app.NotificationChannel#getId`", d[1].message)
        assertEquals("Call requires API level 30 (current min is 21): `android.view.View#getStateDescription`", d[2].message)
    }

    @Test
    fun setterAccessorIsLookedUpAsTheSetter() {
        val d = newApi("fun m(v: View) { v.stateDescription = \"x\" }")
        assertEquals(listOf("Call requires API level 30 (current min is 21): `android.view.View#setStateDescription`"), d.map { it.message })
    }

    @Test
    fun minSdkAtTheLevelIsClean() {
        assertEquals(emptyList(), newApi("fun m() { NotificationChannel(\"id\") }", minSdk = 26))
    }

    @Test
    fun ifWhenAndAndGuardsCoverTheirBranch() {
        assertEquals(emptyList(), newApi("""
            fun m(v: View) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { NotificationChannel("id") }
                if (Build.VERSION.SDK_INT < 26) { } else NotificationChannel("id")
                when {
                    Build.VERSION.SDK_INT >= 30 -> v.stateDescription
                    Build.VERSION.SDK_INT < 26 -> null
                    else -> NotificationChannel("id")
                }
                val ok = Build.VERSION.SDK_INT >= 30 && v.stateDescription != null
            }"""))
        assertEquals(1, newApi("fun m() { if (Build.VERSION.SDK_INT >= 25) NotificationChannel(\"id\") }").size, "25 is not enough")
    }

    @Test
    fun earlyReturnGuardsTheRestOfTheBlock() {
        assertEquals(emptyList(), newApi("fun m() { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return; NotificationChannel(\"id\") }"))
        assertEquals(1, newApi("fun m() { NotificationChannel(\"id\"); if (Build.VERSION.SDK_INT < 26) return }").size)
    }

    @Test
    fun requiresApiCoversTheDeclaration() {
        assertEquals(emptyList(), newApi("@RequiresApi(26) fun m() { NotificationChannel(\"id\") }"))
        assertEquals(emptyList(), newApi("@RequiresApi(api = Build.VERSION_CODES.O) class C { fun m() { NotificationChannel(\"id\") } }"))
        assertEquals(1, newApi("@RequiresApi(24) fun m() { NotificationChannel(\"id\") }").size)
    }

    @Test
    fun completionDocsOpenWithTheApiLevel() {
        minSdkFor = 21
        val items = runBlocking {
            analyzer.completeAtCaret(srcDir, "Doc.kt", "import android.app.NotificationChannel\nfun f(c: NotificationChannel) { c.get| }")
        }.items
        val item = items.first { it.symbol?.name == "getId" }
        assertEquals("Added in API 26", item.documentation)
    }

    private fun applyFixes(code: String, fileName: String): List<Pair<String, String>> {
        minSdkFor = 21
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            val d = analyzer.analyze(doc.file).diagnostics.first { it.code == KotlinDiagnosticCodes.NEW_API }
            analyzer.newApiFixes(doc.file, d.range.start, 26).map { fix ->
                fix.title to fix.edits.sortedByDescending { it.offset }
                    .fold(code) { t, e -> t.substring(0, e.offset) + e.newText + t.substring(e.offset + e.oldLength) }
            }
        }
    }

    @Test
    fun fixesAnnotateTheFunctionOrGuardTheStatement() {
        val code = "package demo\n\nimport android.app.NotificationChannel\n\nclass C {\n    fun m() {\n        NotificationChannel(\"id\")\n    }\n}\n"
        val fixes = applyFixes(code, "Fix1.kt")
        assertEquals(listOf("Add @TargetApi(26) to 'm'", "Surround with if (Build.VERSION.SDK_INT >= 26)"), fixes.map { it.first })
        val annotated = fixes[0].second
        assertTrue("    @TargetApi(26)\n    fun m() {" in annotated, annotated)
        assertTrue("import android.annotation.TargetApi" in annotated, annotated)
        val guarded = fixes[1].second
        assertTrue("        if (Build.VERSION.SDK_INT >= 26) {\n            NotificationChannel(\"id\")\n        }" in guarded, guarded)
        assertTrue("import android.os.Build" in guarded, guarded)
    }

    @Test
    fun aLocalDeclarationIsNotWrapped() {
        val code = "package demo\nimport android.app.NotificationChannel\nfun m() {\n    val c = NotificationChannel(\"id\")\n}\n"
        assertEquals(listOf("Add @TargetApi(26) to 'm'"), applyFixes(code, "Fix2.kt").map { it.first })
    }

    @Test
    fun noContextMeansNoCheck() {
        minSdkFor = null
        val doc = SnippetDoc("package demo\nfun m() { android.app.NotificationChannel(\"id\") }", DiskFile(srcDir.resolve("Plain.kt")))
        val d = runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
        assertTrue(d.none { it.code == KotlinDiagnosticCodes.NEW_API }, d.toString())
    }

    @Test
    fun aJvmModuleChecksJdkApisAgainstItsJavaRelease() {
        fun check(release: Int): List<Diagnostic> {
            jdkRelease = release
            try {
                val doc = SnippetDoc(
                    "package demo\nfun m(a: String?): String {\n    return java.util.Objects.requireNonNullElse(a, \"x\")\n}\n",
                    DiskFile(srcDir.resolve("Jdk${counter.incrementAndGet()}.kt")),
                )
                val all = runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
                return all.filter { it.code == KotlinDiagnosticCodes.NEW_JDK_API }
            } finally {
                jdkRelease = null
            }
        }
        val d = check(8)
        assertEquals(listOf("Call requires Java 9 (module language level is 8): `java.util.Objects#requireNonNullElse`"), d.map { it.message })
        assertTrue(d.all { it.severity == Severity.ERROR })
        assertEquals(emptyList(), check(9))
    }

    companion object {
        private val counter = AtomicInteger()
        @Volatile private var minSdkFor: Int? = 21
        @Volatile private var jdkRelease: Int? = null

        private val jdk = object : PlatformApiLevels {
            override val platformLevel = 25
            override fun classLevel(className: String): ApiLevel? = null
            override fun methodLevel(ownerClass: String, name: String, descriptor: String?, arity: Int) =
                if (ownerClass.replace('/', '.') == "java.util.Objects" && name == "requireNonNullElse") ApiLevel(9) else null
            override fun fieldLevel(ownerClass: String, name: String): ApiLevel? = null
        }

        private val levels = object : PlatformApiLevels {
            override val platformLevel = 36
            private val methods = mapOf(
                "android.app.NotificationChannel#<init>" to ApiLevel(26),
                "android.app.NotificationChannel#getId" to ApiLevel(26),
                "android.view.View#getStateDescription" to ApiLevel(30),
                "android.view.View#setStateDescription" to ApiLevel(30),
            )
            override fun classLevel(className: String) =
                if (className.replace('$', '.') == "android.app.NotificationChannel") ApiLevel(26) else null
            override fun methodLevel(ownerClass: String, name: String, descriptor: String?, arity: Int) =
                methods["${ownerClass.replace('$', '.')}#$name"]
            override fun fieldLevel(ownerClass: String, name: String): ApiLevel? = null
        }

        val srcDir: Path = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), platformJar())))
            .also { a ->
                a.apiContext = {
                    jdkRelease?.let { ApiLevelContext.jdk(it, jdk, enforced = true) }
                        ?: minSdkFor?.let { ApiLevelContext.android(it, levels) }
                }
            }

        private fun platformJar(): Path {
            val jar = Files.createTempFile("platform", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                fun put(name: String, bytes: ByteArray) {
                    zos.putNextEntry(ZipEntry("$name.class")); zos.write(bytes); zos.closeEntry()
                }
                put("android/app/NotificationChannel", classBytes("android/app/NotificationChannel") {
                    method("<init>", "(Ljava/lang/String;)V")
                    method("getId", "()Ljava/lang/String;")
                })
                // The test analyzer has no JDK; a stub stands in for the one JDK class the JDK case calls.
                put("java/util/Objects", classBytes("java/util/Objects") {
                    visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "requireNonNullElse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "<T:Ljava/lang/Object;>(TT;TT;)TT;", null).apply {
                        visitCode(); visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN); visitMaxs(1, 2); visitEnd()
                    }
                })
                put("android/view/View", classBytes("android/view/View") {
                    method("<init>", "()V")
                    method("getStateDescription", "()Ljava/lang/CharSequence;")
                    method("setStateDescription", "(Ljava/lang/CharSequence;)V")
                })
            }
            return jar
        }

        private fun classBytes(name: String, members: ClassWriter.() -> Unit): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
            cw.members()
            cw.visitEnd()
            return cw.toByteArray()
        }

        private fun ClassWriter.method(name: String, descriptor: String) {
            visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(1, 2); visitEnd()
            }
        }
    }
}
