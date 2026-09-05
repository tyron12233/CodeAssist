package dev.ide.lang.kotlin

import dev.ide.index.ClassNameExternalizer
import dev.ide.index.ClassNameValue
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.classEntryToFqn
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.index.nestedClassEntryToFqn
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Import fix over a library type used by simple name, driven off the class-NAME index: a NESTED type
 * (`LinearLayout.LayoutParams`) must be offered like a top-level one, and the import it inserts must actually
 * resolve the reference. Nested library types were missing from the index entirely, so an unresolved
 * `LayoutParams` got the error with no fix beside it.
 *
 * The library half of that index is produced in `:lang-java` (`JavaClassNamesIndex`, pinned by
 * `JavaNestedTypeIndexTest`), which this module does not depend on, so [LibraryClassNames] below mirrors its
 * keying over the same class entries.
 */
class KotlinNestedTypeImportTest {

    private fun diagnostics(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun importFixes(code: String, name: String): List<String> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.analyze(doc.file)
            analyzer.importFixesAt(doc.file, code.indexOf(name) + 1)
        }.map { it.title }
    }

    @Test
    fun nestedLibraryTypeIsOfferedForImport() {
        assumeTrue(androidJar != null, "no android.jar; skipping nested-type import test")
        val titles = importFixes("package demo\nfun f() { val p = LayoutParams(0, 0); println(p) }\n", "LayoutParams")
        assertTrue(
            "Import android.widget.LinearLayout.LayoutParams" in titles,
            "a nested library type must be offered for import; got $titles",
        )
    }

    @Test
    fun theOfferedNestedImportResolvesTheReference() {
        assumeTrue(androidJar != null, "no android.jar; skipping nested-type import test")
        // The bar for offering a fix: accepting it leaves no error behind.
        val diags = diagnostics(
            "package demo\nimport android.widget.LinearLayout.LayoutParams\n" +
                "fun f() { val p = LayoutParams(0, 0); println(p.weight) }\n",
        )
        assertTrue(diags.isEmpty(), "the inserted nested import must resolve the reference; got $diags")
    }

    @Test
    fun topLevelLibraryTypeIsStillOffered() {
        assumeTrue(androidJar != null, "no android.jar; skipping nested-type import test")
        val titles = importFixes("package demo\nval p: ViewOutlineProvider? = null\n", "ViewOutlineProvider")
        assertTrue(
            "Import android.view.ViewOutlineProvider" in titles,
            "a top-level library type must keep its import fix; got $titles",
        )
    }

    /** The `java.classNames` producer's keying (see the class KDoc): a class entry under its simple name, with
     *  the dotted FQN an `import` spells; synthetic `$` names are left out. */
    private object LibraryClassNames : IndexExtension<String, ClassNameValue> {
        override val id = IndexId("java.classNames")
        override val version = 1
        override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
        override val valueExternalizer = ClassNameExternalizer
        override val matching = MatchingMode.PREFIX_AND_FUZZY
        override val inputFilter = InputFilter {
            (it.origin == IndexOrigin.LIBRARY || it.origin == IndexOrigin.SDK) && it.unitName?.endsWith(".class") == true
        }

        override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
            val entry = input.unitName!!
            val (fqn, simple) = classEntryToFqn(entry) ?: nestedClassEntryToFqn(entry) ?: return emptyMap()
            return mapOf(simple to listOf(ClassNameValue(fqn, input.origin, "class")))
        }
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        private val androidJar: Path? = listOfNotNull(
            System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
            System.getProperty("user.home") + "/Library/Android/sdk",
        ).map { Path.of(it) }.filter { Files.isDirectory(it) }
            .map { it.resolve("platforms") }.filter { Files.isDirectory(it) }
            .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
            .map { it.resolve("android.jar") }.filter { Files.isRegularFile(it) }
            .maxByOrNull { it.parent.fileName.toString() }
        private val jars = listOfNotNull(stdlibJarPath(), androidJar)
        private val index = IndexServiceImpl(
            listOf(KotlinTypeShapeIndex, KotlinCallableIndex, LibraryClassNames),
            Files.createTempDirectory("nested-import-idx"),
        ).also { if (androidJar != null) runBlocking { it.ensureUpToDate(IndexScope(libraryJars = jars)) } }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = jars)).also { it.indexService = index }
    }
}
