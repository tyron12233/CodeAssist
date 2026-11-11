package dev.ide.lang.kotlin

import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.kotlin.index.KotlinBuiltinsIndex
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import dev.ide.lang.resolve.ApiLevel
import dev.ide.lang.resolve.ApiLevelContext
import dev.ide.lang.resolve.PlatformApiLevels
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Java API that Android does not have is explained, not just rejected. `Files.writeString` (Java 11) is absent
 * from `android.jar` before API 37, so in an Android module it cannot resolve; with the JDK's table at hand the
 * message says it is real Java this platform lacks and what to use, where a bare "Unresolved reference" read as
 * the user's own mistake. Runs against the IDE's bundled `android.jar` (API 36), so it skips without one.
 */
class KotlinJavaApiMissingOnAndroidTest {

    private val code = """
        package demo
        import java.nio.file.Files
        import java.nio.file.Path
        fun write(file: Path, text: String): String {
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
            Files.noSuchThing(file)
            return Files.readString(file)
        }
    """.trimIndent()

    // One file per context: an analysis is kept per file, and the context is not part of what invalidates it.
    private fun unresolved(context: ApiLevelContext?, fileName: String): List<String> {
        assumeTrue(androidJar != null, "no bundled android.jar; skipping")
        analyzer.apiContext = { context }
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.analyze(doc.file).diagnostics }.filter { it.code == "kt.unresolved" }.map { it.message }
    }

    @Test
    fun aJavaApiAndroidLacksIsExplainedWithWhatToUse() {
        val messages = unresolved(ApiLevelContext.android(26, android36) { jdk }, "Explained.kt")
        assertEquals(3, messages.size, "createDirectories resolves; the other three do not: $messages")
        val write = messages.single { "writeString" in it }
        assertTrue(write.startsWith("Unresolved reference: writeString. "), write)
        assertTrue("'Files.writeString' is Java 11 API that Android API 36 does not have." in write, write)
        assertTrue("path.writeText(text)" in write, write)
        assertTrue("path.readText()" in messages.single { "readString" in it })
        // Not Java API at all: nothing to explain.
        assertEquals("Unresolved reference: noSuchThing", messages.single { "noSuchThing" in it })
    }

    @Test
    fun anImportOfAJavaClassAndroidLacksIsExplainedWithWhatToUse() {
        assumeTrue(androidJar != null, "no bundled android.jar; skipping")
        analyzer.apiContext = { ApiLevelContext.android(26, android36) { jdk } }
        val text = "package demo\nimport java.net.http.HttpClient\nimport java.util.ArrayList\nfun f() {}\n"
        val doc = SnippetDoc(text, DiskFile(srcDir.resolve("Imports.kt")))
        analyzer.incrementalParser.parseFull(doc)
        val messages = runBlocking { analyzer.analyze(doc.file).diagnostics }.filter { it.code == "kt.unresolved" }.map { it.message }
        assertEquals(
            listOf("Unresolved reference: HttpClient. 'java.net.http.HttpClient' is Java 11 API that Android API 36 does not have. Use 'java.net.HttpURLConnection', or a library such as OkHttp."),
            messages,
        )
    }

    @Test
    fun withoutTheJdkTableTheMessageIsUnchanged() {
        val messages = unresolved(ApiLevelContext.android(26, android36), "Plain.kt")
        assertTrue("Unresolved reference: writeString" in messages, "$messages")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))

        /** The IDE's own platform jar (API 36), which is what a phone analyzes against. */
        private val androidJar: Path? = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("app/ide-android/src/main/sdk/android.jar") }.firstOrNull { Files.isRegularFile(it) }
        private val jars = listOfNotNull(stdlibJarPath(), androidJar)
        private val index = IndexServiceImpl(listOf(KotlinTypeShapeIndex, KotlinBuiltinsIndex, KotlinCallableIndex), Files.createTempDirectory("java-api-android-idx"))
            .also { if (androidJar != null) runBlocking { it.ensureUpToDate(IndexScope(libraryJars = jars)) } }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = jars)).also { it.indexService = index }

        private val android36 = object : PlatformApiLevels {
            override val platformLevel = 36
            override fun classLevel(className: String): ApiLevel? = null
            override fun methodLevel(ownerClass: String, name: String, descriptor: String?, arity: Int): ApiLevel? = null
            override fun fieldLevel(ownerClass: String, name: String): ApiLevel? = null
        }

        /** The JDK as far as this test needs it: the two Java 11 `Files` methods. */
        private val jdk = object : PlatformApiLevels {
            override val platformLevel = 25
            override fun classLevel(className: String): ApiLevel? = if (className == "java.net.http.HttpClient") ApiLevel(11) else null
            override fun methodLevel(ownerClass: String, name: String, descriptor: String?, arity: Int): ApiLevel? =
                if (ownerClass == "java.nio.file.Files" && (name == "writeString" || name == "readString")) ApiLevel(11) else null
            override fun fieldLevel(ownerClass: String, name: String): ApiLevel? = null
        }
    }
}
