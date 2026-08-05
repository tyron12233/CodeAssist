package dev.ide.lang.kotlin

import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * A builder chain that ends in `.build()` must infer the built type, so a member off the result resolves —
 * the reported `Retrofit.Builder().…​.build()` then `.create()` case. The fixture is a real Java BINARY
 * (`dev.ide.fakeretrofit`, compiled from src/test/java) so it exercises the bytecode nested-class path the
 * live retrofit2 library takes, not the source model. Localized: the nested-class constructor, each
 * self-returning link, a link whose argument is a nested static-factory call, and the final `build()` result.
 */
class KotlinBuilderChainInferenceTest {

    private fun diagnose(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun unresolved(code: String, name: String): Boolean =
        diagnose(code).any { it.code == "kt.unresolved" && name in it.message }

    private val imports =
        "import dev.ide.fakeretrofit.Retrofit\nimport dev.ide.fakeretrofit.GsonConverterFactory\n"

    @Test
    fun nestedBuilderCtorMemberResolves() {
        val code = "package demo\n$imports" + "fun test() {\n  val b = Retrofit.Builder()\n  b.baseUrl(\"\")\n}"
        assertFalse(unresolved(code, "baseUrl"), "Retrofit.Builder() must type as Builder: ${diagnose(code)}")
    }

    @Test
    fun selfReturningLinkResolves() {
        val code = "package demo\n$imports" + "fun test() {\n  val b = Retrofit.Builder().baseUrl(\"\")\n  b.build()\n}"
        assertFalse(unresolved(code, "build"), "baseUrl() returns Builder: ${diagnose(code)}")
    }

    @Test
    fun linkWithNestedCallArgResolves() {
        val code = "package demo\n$imports" +
            "fun test() {\n  val b = Retrofit.Builder().addConverterFactory(GsonConverterFactory.create())\n  b.build()\n}"
        assertFalse(unresolved(code, "build"), "a nested static-factory arg must not break the chain: ${diagnose(code)}")
    }

    @Test
    fun buildResultMemberResolves() {
        val code = "package demo\n$imports" +
            "fun test() {\n" +
            "  val a = Retrofit.Builder().baseUrl(\"\").addConverterFactory(GsonConverterFactory.create()).build()\n" +
            "  a.create()\n}"
        assertFalse(unresolved(code, "create"), "a: Retrofit (via build()); a.create() must resolve: ${diagnose(code)}")
    }

    @Test
    fun topLevelPropertyBuildResultMemberResolves() {
        // `a` as a same-file TOP-LEVEL property (not a local val) — resolved via live-buffer PSI inference.
        val code = "package demo\n$imports" +
            "val a = Retrofit.Builder().baseUrl(\"\").addConverterFactory(GsonConverterFactory.create()).build()\n" +
            "fun test() { a.create() }"
        assertFalse(unresolved(code, "create"), "top-level a: Retrofit; a.create() must resolve: ${diagnose(code)}")
    }

    @Test
    fun crossFileTopLevelPropertyBuildResultMemberResolves() {
        // `apiClient` is a top-level property in ANOTHER file (Api.kt, in the disk source model). Its type must
        // come from PSI inference of its initializer chain (inferReturnFromBody), NOT the crude text guess that
        // would mistype it as `Retrofit.Builder`.
        val code = "package demo\nfun test() { apiClient.create() }"
        assertFalse(unresolved(code, "create"), "cross-file apiClient: Retrofit; .create() must resolve: ${diagnose(code)}")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Use.kt" to "package demo\n",
                "Api.kt" to "package demo\nimport dev.ide.fakeretrofit.Retrofit\nimport dev.ide.fakeretrofit.GsonConverterFactory\n" +
                    "val apiClient = Retrofit.Builder().baseUrl(\"\").addConverterFactory(GsonConverterFactory.create()).build()\n",
            ),
        )
        private val jars = listOf(fakeRetrofitJar(), stdlibJarPath())
        // A READY type-shape/callable INDEX over the fixture jar — the real-IDE (device) condition. The live
        // reader tolerates a bytecode `$`-nested FQN via classBytes, so a `$`-vs-`.` gap only reproduces here.
        private val index = IndexServiceImpl(
            listOf(KotlinTypeShapeIndex, KotlinCallableIndex), Files.createTempDirectory("retrofit-idx"),
        ).also { runBlocking { it.ensureUpToDate(IndexScope(libraryJars = jars)) } }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = jars)).also { it.indexService = index }

        /** Stage the compiled Java fixture classes into a jar (mirrors KotlinBareExtensionImportTest.fakeExtJar). */
        private fun fakeRetrofitJar(): Path {
            val jar = Files.createTempFile("fake-retrofit", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                fun add(name: String) {
                    val bytes = KotlinBuilderChainInferenceTest::class.java.classLoader.getResourceAsStream(name)
                        ?.use { it.readBytes() } ?: error("missing class resource $name")
                    zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
                }
                add("dev/ide/fakeretrofit/Factory.class")
                add("dev/ide/fakeretrofit/Retrofit.class")
                add("dev/ide/fakeretrofit/Retrofit\$Builder.class")
                add("dev/ide/fakeretrofit/GsonConverterFactory.class")
            }
            return jar
        }
    }
}
