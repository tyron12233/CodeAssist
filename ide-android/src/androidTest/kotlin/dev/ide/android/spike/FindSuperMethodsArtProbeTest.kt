package dev.ide.android.spike

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import dev.ide.android.AndroidIde
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.psi.IntellijPsiHost
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Root-cause probe for the on-device `@Override` false positive: `PsiMethod.findSuperMethods()` returns EMPTY
 * on ART for a genuine override against a decompiled (`Cls`) library class, even though the type resolves and
 * the same call returns the super method on desktop. This localizes WHERE the ART path diverges by measuring
 * each layer independently:
 *   - resolution:      does the supertype reference resolve? (expected: yes — the reported case had types resolving)
 *   - own-method read: does the resolved super's OWN method list contain the name? (the fix's primitive)
 *   - hierarchy layer: getAllMethods / visibleSignatures / hierarchicalMethodSignature (the cached, generic-
 *                      substituted signature machinery `findSuperMethods` is built on)
 *   - findSuperMethods: the API that misbehaves
 *
 * A green run only asserts the fix's primitives (resolve + own-method read) hold on ART; the DIAGNOSIS is in
 * logcat — compare each layer's count to desktop (where findSuperMethods=1) to see which layer drops to 0.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.FindSuperMethodsArtProbeTest
 *     adb logcat -s FindSuperMethodsArtProbe
 */
@RunWith(AndroidJUnit4::class)
class FindSuperMethodsArtProbeTest {

    @Test
    fun probeFindSuperMethodsOnArt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(ctx.filesDir, "find-super-art").apply { deleteRecursively(); mkdirs() }
        val home = provisionKotlincHome(ctx, File(work, "kotlinc-home"))
        System.setProperty("kotlinc.art.home", home.absolutePath)
        val androidJar = AndroidIde.provisionAndroidJar(ctx)
        Log.i(TAG, "kotlinc.art.home=$home  android.jar=$androidJar (${androidJar.length()} bytes)")

        IntellijPsiHost.warmUp()
        val srcRoot = File(work, "src").apply { mkdirs() }
        // ART: no JDK — android.jar is the platform (jdkHome=null → NO_JDK), exactly like a real Android module.
        val env = JavaEnvironment.create(listOf(androidJar), listOf(srcRoot), jdkHome = null)

        val report = ArrayList<String>()
        try {
            // Case 1: `X extends android.app.Activity` with @Override onCreate — the MainActivity shape.
            probe(
                env, srcRoot, report, label = "extends-Activity", methodName = "onCreate",
                src = "package com.foo;\npublic class X extends android.app.Activity {\n" +
                    "  @Override protected void onCreate(android.os.Bundle b) { super.onCreate(b); }\n}",
            )
            // Case 2: anonymous android.view.View.OnClickListener with @Override onClick — the listener shape.
            probe(
                env, srcRoot, report, label = "anon-OnClickListener", methodName = "onClick",
                src = "package com.foo;\npublic class X {\n" +
                    "  Object o = new android.view.View.OnClickListener() {\n" +
                    "    @Override public void onClick(android.view.View v) { }\n  };\n}",
            )
        } finally {
            env.close()
            // Deterministic pull point (logcat can rotate during the build): <ext-files>/find-super-report.txt.
            val out = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "find-super-report.txt")
            out.writeText(report.joinToString("\n") + "\n")
            Log.i(TAG, "report written to $out")
        }
    }

    private fun probe(env: JavaEnvironment, srcRoot: File, report: MutableList<String>, label: String, methodName: String, src: String) {
        File(srcRoot, "com/foo/X.java").apply { parentFile.mkdirs(); writeText(src) }
        val file = env.parse("com/foo/X.java", src) as PsiJavaFile
        IntellijPsiHost.withParseLock {
            val m = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java)
                .firstOrNull { it.name == methodName && it.hasAnnotation("java.lang.Override") }
            if (m == null) { Log.e(TAG, "[$label] could not find @Override $methodName in parsed tree"); return@withParseLock }
            val cls: PsiClass = m.containingClass!!

            val superTypes = runCatching { cls.superTypes.map { it.resolve()?.qualifiedName ?: "<unresolved>" } }
                .getOrElse { listOf("EXC:${it}") }
            val superClassName = runCatching { cls.superClass?.qualifiedName }.getOrElse { "EXC:$it" }
            // Own-method read on the resolved supers (the fix's primitive).
            val ownScan = runCatching {
                cls.superTypes.mapNotNull { it.resolve() }.sumOf { it.findMethodsByName(methodName, false).size }
            }.getOrElse { -1 }
            // Hierarchy layers findSuperMethods is built on.
            val allMethods = runCatching { cls.allMethods.count { it.name == methodName } }.getOrElse { -1 }
            val visibleSigs = runCatching { cls.visibleSignatures.count { it.name == methodName } }.getOrElse { -1 }
            val hierSupers = runCatching { m.hierarchicalMethodSignature.superSignatures.size }.getOrElse { -1 }
            // The misbehaving API.
            val supers = runCatching { m.findSuperMethods().map { it.containingClass?.qualifiedName } }
                .getOrElse { listOf("EXC:${it}") }
            val deepest = runCatching { m.findDeepestSuperMethods().size }.getOrElse { -1 }

            val lines = listOf(
                "[$label] superClass=$superClassName superTypes=$superTypes",
                "[$label] ownScanNamed=$ownScan  allMethodsNamed=$allMethods  visibleSigsNamed=$visibleSigs  hierSuperSigs=$hierSupers",
                "[$label] findSuperMethods=${supers.size} $supers  findDeepestSuperMethods=$deepest",
            )
            lines.forEach { Log.i(TAG, it); report += it }

            // The fix's foundation MUST hold on ART: the supertype resolves and its own method list has the name.
            assertTrue("[$label] supertype must resolve on ART", superTypes.none { it == "<unresolved>" || it.startsWith("EXC") })
            assertTrue("[$label] own-method read (the fix's primitive) must find '$methodName' on ART; got $ownScan", ownScan >= 1)
            // After the IntrospectorArtPass shim, findSuperMethods must no longer throw NoClassDefFoundError:
            // java.beans.Introspector — it must return the real super method. (Pre-shim this was EXC → this asserts
            // the shim works end-to-end on device.)
            val fsmOk = supers.isNotEmpty() && supers.none { it?.startsWith("EXC") == true }
            assertTrue("[$label] findSuperMethods must succeed on ART after the Introspector shim; got $supers", fsmOk)
        }
    }

    private fun provisionKotlincHome(ctx: Context, home: File): File {
        home.deleteRecursively(); home.mkdirs()
        val canonicalHome = home.canonicalPath + File.separator
        ctx.assets.open("kotlinc-resources.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(home, entry.name)
                    if (outFile.canonicalPath.startsWith(canonicalHome)) {
                        if (entry.isDirectory) outFile.mkdirs()
                        else { outFile.parentFile?.mkdirs(); outFile.outputStream().use { zis.copyTo(it) } }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return home
    }

    private companion object { const val TAG = "FindSuperMethodsArtProbe" }
}
