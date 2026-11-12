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
import dev.ide.index.StringExternalizer
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.classEntryToFqn
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An unresolved bare call offers to import a Java class's STATIC of that name: `assertEquals(…)` in a Kotlin
 * test, with JUnit on the classpath and nothing imported yet, offers `org.junit.jupiter.api.Assertions.assertEquals`.
 * A Java static is not a Kotlin callable, so the callable indexes the Import fix read never held one, and the
 * lightbulb on the most common first line of a test had no import to offer.
 *
 * The `java.staticMembers` and `java.classNames` producers live in `:lang-java`, which this module cannot see, so
 * their keying is mirrored here (as `KotlinNestedTypeImportTest` does for class names).
 */
class KotlinJavaStaticImportFixTest {

    private fun importFixes(code: String, name: String): List<String> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.analyze(doc.file)
            analyzer.importFixesAt(doc.file, code.indexOf(name) + 1)
        }.map { it.title }
    }

    @Test
    fun aJavaStaticIsOfferedForAnUnresolvedBareCall() {
        val titles = importFixes("package demo\nfun t() {\n    assertEquals(1, 1)\n}\n", "assertEquals")
        assertTrue("Import org.junit.jupiter.api.Assertions.assertEquals" in titles, "got $titles")
    }

    @Test
    fun acceptingTheImportResolvesTheCall() {
        val code = "package demo\nimport org.junit.jupiter.api.Assertions.assertEquals\nfun t() {\n    assertEquals(1, 1)\n}\n"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        val diags = runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
        assertTrue(diags.none { it.code == "kt.unresolved" }, "got $diags")
    }

    @Test
    fun anInstanceMemberOfTheSameNameIsNotOffered() {
        // `TestInfo.getDisplayName` is an instance method: importing it by name would not compile.
        val titles = importFixes("package demo\nfun t() {\n    getDisplayName()\n}\n", "getDisplayName")
        assertTrue(titles.none { "getDisplayName" in it }, "got $titles")
    }

    @Test
    fun aQualifiedCallIsNotOfferedAStatic() {
        val titles = importFixes("package demo\nfun t(s: String) {\n    s.assertEquals(1, 1)\n}\n", "assertEquals")
        assertTrue(titles.none { "Assertions.assertEquals" in it }, "got $titles")
    }

    private object LibraryClassNames : IndexExtension<String, ClassNameValue> {
        override val id = IndexId("java.classNames")
        override val version = 1
        override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
        override val valueExternalizer = ClassNameExternalizer
        override val matching = MatchingMode.PREFIX_AND_FUZZY
        override val inputFilter = InputFilter { it.origin == IndexOrigin.LIBRARY && it.unitName?.endsWith(".class") == true }

        override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
            val (fqn, simple) = classEntryToFqn(input.unitName!!) ?: return emptyMap()
            val public = runCatching { java.lang.reflect.Modifier.isPublic(Class.forName(fqn, false, javaClass.classLoader).modifiers) }
                .getOrDefault(false)
            return if (public) mapOf(simple to listOf(ClassNameValue(fqn, input.origin, "class"))) else emptyMap()
        }
    }

    /** `java.staticMembers`' keying: each public static method name of a public top-level class, to that class. */
    private object LibraryStatics : IndexExtension<String, String> {
        override val id = IndexId("java.staticMembers")
        override val version = 1
        override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
        override val valueExternalizer = StringExternalizer
        override val matching = MatchingMode.PREFIX_ONLY
        override val inputFilter = InputFilter { it.origin == IndexOrigin.LIBRARY && it.unitName?.endsWith(".class") == true }

        override fun index(input: IndexInput): Map<String, Collection<String>> {
            val owner = input.unitName!!.removeSuffix(".class").replace('/', '.').takeIf { '$' !in it } ?: return emptyMap()
            val cls = runCatching { Class.forName(owner, false, javaClass.classLoader) }.getOrNull() ?: return emptyMap()
            if (!java.lang.reflect.Modifier.isPublic(cls.modifiers)) return emptyMap()
            val methods = runCatching { cls.declaredMethods }.getOrNull() ?: return emptyMap()
            return methods.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }.distinct().associateWith { listOf(owner) }
        }
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        private val jars = listOf(stdlibJarPath(), TestJars.onClasspath("org/junit/jupiter/api/Assertions.class"))
        private val index = IndexServiceImpl(
            listOf(KotlinTypeShapeIndex, KotlinCallableIndex, LibraryClassNames, LibraryStatics),
            Files.createTempDirectory("static-import-idx"),
        ).also { runBlocking { it.ensureUpToDate(IndexScope(libraryJars = jars)) } }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = jars)).also { it.indexService = index }
    }
}
