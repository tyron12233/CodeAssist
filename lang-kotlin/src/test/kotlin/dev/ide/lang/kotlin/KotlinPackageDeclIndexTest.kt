package dev.ide.lang.kotlin

import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.lang.kotlin.index.KotlinPackageDeclIndex
import dev.ide.lang.kotlin.index.PkgDecl
import dev.ide.lang.kotlin.index.PkgDeclExternalizer
import dev.ide.platform.ContentHash
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `kotlin.pkgDecls` index — per-package enumeration of a library's top-level Kotlin classifiers +
 * callables, backing the K2/AA STUBS-mode declaration provider. Verifies the PRODUCER over the REAL stdlib
 * jar: a top-level class surfaces as a package classifier, a top-level function surfaces as a non-classifier
 * callable carrying its facade FQN, subpackage keys are present (so `getKotlinOnlySubpackageNames`'s prefix
 * scan works), and the codec round-trips.
 */
class KotlinPackageDeclIndexTest {

    @Test
    fun topLevelClassIsAPackageClassifier() {
        val kotlinPkg = served["kotlin"].orEmpty()
        val pair = assertNotNull(kotlinPkg.firstOrNull { it.name == "Pair" }, "kotlin.Pair must be present; sample=${kotlinPkg.take(8).map { it.name }}")
        assertTrue(pair.classifier, "kotlin.Pair must be a package classifier")
        assertEquals(null, pair.facade, "a classifier carries no facade (located by FQN via classLocator)")
    }

    @Test
    fun topLevelFunctionIsACallableWithFacade() {
        // kotlin.io.println lives in the file facade kotlin/io/ConsoleKt.
        val ioPkg = served["kotlin.io"].orEmpty()
        val println = assertNotNull(ioPkg.firstOrNull { it.name == "println" && !it.classifier }, "kotlin.io.println must be a top-level callable; sample=${ioPkg.take(8).map { it.name }}")
        assertTrue(println.facade?.endsWith("ConsoleKt") == true, "callable carries its facade FQN; got ${println.facade}")
    }

    @Test
    fun classifiersAreNotConfusedWithCallables() {
        // A package's classifier name-set and callable name-set are the two filtered views the provider serves.
        val text = served["kotlin.text"].orEmpty()
        assertTrue(text.any { it.classifier && it.name == "Regex" }, "kotlin.text.Regex is a classifier")
        assertTrue(text.any { !it.classifier }, "kotlin.text also has top-level callables (StringsKt)")
    }

    @Test
    fun topLevelExtensionsAreCallables() {
        // `String.trim` is a top-level EXTENSION function (kotlin.text). getTopLevelFunctions returns it, so its
        // name MUST be in the callable name-set (the mayHaveTopLevelCallable superset invariant) — @Metadata
        // keeps extensions apart from plain top-levels, so the index must fold both in.
        val text = served["kotlin.text"].orEmpty()
        assertTrue(text.any { !it.classifier && it.name == "trim" }, "kotlin.text.trim (extension) must enumerate as a callable")
    }

    @Test
    fun subpackageKeysArePresent() {
        // getKotlinOnlySubpackageNames("kotlin") does prefix("kotlin.") over these keys, taking each next segment.
        assertTrue("kotlin.collections" in served.keys, "expected kotlin.collections key; sample=${served.keys.take(12)}")
        assertTrue("kotlin.text" in served.keys)
    }

    @Test
    fun codecRoundTrips() {
        val v = PkgDecl("foo", classifier = false, facade = "a.b.FooKt")
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { PkgDeclExternalizer.write(it, v) }
        val back = DataInputStream(ByteArrayInputStream(bos.toByteArray())).use { PkgDeclExternalizer.read(it) }
        assertEquals(v.name, back.name)
        assertEquals(v.classifier, back.classifier)
        assertEquals(v.facade, back.facade)
        // classifier form: null facade round-trips as null (not "")
        val c = DataInputStream(ByteArrayInputStream(ByteArrayOutputStream().also { b -> DataOutputStream(b).use { PkgDeclExternalizer.write(it, PkgDecl("C", true, null)) } }.toByteArray())).use { PkgDeclExternalizer.read(it) }
        assertEquals(null, c.facade)
    }

    @Test
    fun topLevelTypeAliasesEnumerateAsClassifiersWithFacade() {
        // Stdlib JVM has essentially no top-level typealiases; coroutines-core does (e.g. CompletionHandler).
        // A typealias has no `.class` of its own, so it must enumerate as a classifier CARRYING its facade FQN
        // (else getClassLikeDeclarationByClassId can't locate it and the classifier name-set drops it).
        val coroutines = coroutinesJarPath() ?: return
        val entries = indexJar(coroutines)
        val typeAliases = entries.values.flatten().filter { it.classifier && it.facade != null }
        println("coroutines top-level typealiases (sample): ${typeAliases.take(20).map { "${it.name}@${it.facade}" }}")
        assertTrue(typeAliases.isNotEmpty(), "coroutines must contribute top-level typealias classifier entries (facade-carried)")
    }

    companion object {
        private val served: Map<String, List<PkgDecl>> = indexJar(stdlibJarPath())

        private fun indexJar(jar: Path): Map<String, List<PkgDecl>> {
            val out = HashMap<String, MutableList<PkgDecl>>()
            ZipFile(jar.toFile()).use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.name.endsWith(".class")) continue
                    val bytes = z.getInputStream(e).use { it.readBytes() }
                    KotlinPackageDeclIndex.index(FakeInput(e.name, bytes)).forEach { (k, v) ->
                        out.getOrPut(k) { ArrayList() }.addAll(v)
                    }
                }
            }
            return out
        }

        private fun coroutinesJarPath(): Path? {
            val cp = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
            val e = cp.firstOrNull {
                it.contains("kotlinx-coroutines-core") && it.endsWith(".jar") &&
                    runCatching { ZipFile(it).use { z -> z.getEntry("kotlinx/coroutines/Job.class") != null } }.getOrDefault(false)
            } ?: return null
            return Path.of(e)
        }

        private class FakeInput(override val unitName: String, private val b: ByteArray) : IndexInput {
            override val origin = IndexOrigin.LIBRARY
            override val contentHash = ContentHash("")
            override val sourcePath: Path? = null
            override fun bytes() = b
            override fun text(): String? = null
            override fun dom() = null
        }
    }
}
