package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A callback lambda's parameter type must bind from a SIBLING argument that is a SUBTYPE of the callee's
 * generic parameter. The `registerForActivityResult(StartActivityForResult()) { result -> result.data }`
 * shape: `StartActivityForResult : ActivityResultContract<Intent, ActivityResult>` declares no type arguments
 * of its own, so the callback's element type `O` can only be recovered by projecting the contract argument
 * onto `ActivityResultContract` and reading its instantiation. Before the fix, `O` stayed unbound, the lambda
 * parameter was an unbound type parameter, and `result.data` / `data?.` completed nothing. The compiled
 * `dev.ide.fakecompose` fixtures mirror the (binary) AndroidX shape.
 */
class KotlinSiblingGenericInferenceTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    @Test
    fun lambdaParamTypeBindsFromSiblingArgSubtype() {
        // Isolates the fix with a PLAIN function-type callback (no SAM): `O` must bind to `FakeActResult` from
        // `FakeStartActForResult`'s supertype `FakeActContract<FakeActIntent, FakeActResult>`, so the lambda
        // parameter `r` is `FakeActResult` and its `data` member completes.
        val items = labels(
            "package demo\n" +
                "import dev.ide.fakecompose.*\n" +
                "fun use() { regFnCallback(FakeStartActForResult()) { r -> r.dat| } }"
        )
        assertTrue("data" in items, "`r` should infer FakeActResult from the sibling contract subtype; got $items")
    }

    @Test
    fun samCallbackChainCompletesNullableMember() {
        // The full reported shape: a SAM callback (`FakeActivityCallback<O>`), a `result.data` property read
        // into a local, then a SAFE call `data?.` that must complete the nullable member type's members.
        val items = labels(
            "package demo\n" +
                "import dev.ide.fakecompose.*\n" +
                "fun use() {\n" +
                "  regSamCallback(FakeStartActForResult()) { result ->\n" +
                "    val data = result.data\n" +
                "    data?.get|\n" +
                "  }\n" +
                "}"
        )
        assertTrue(
            "getStringExtra" in items && "getLongExtra" in items,
            "`data?.` should complete FakeActIntent members via the SAM callback + generic chain; got $items",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Use.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(fakeActJar(), stdlibJarPath())))

        /** Stage the compiled fake Activity-Result classes into a jar (mirrors KotlinExtensionAutoImportTest). */
        private fun fakeActJar(): Path {
            val jar = Files.createTempFile("fake-act", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                fun add(name: String) {
                    val bytes = KotlinSiblingGenericInferenceTest::class.java.classLoader.getResourceAsStream(name)
                        ?.use { it.readBytes() } ?: error("missing class resource $name")
                    zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
                }
                zos.putNextEntry(ZipEntry("META-INF/fakeact.kotlin_module")); zos.closeEntry()
                add("dev/ide/fakecompose/FakeActIntent.class")
                add("dev/ide/fakecompose/FakeActContract.class")
                add("dev/ide/fakecompose/FakeActResult.class")
                add("dev/ide/fakecompose/FakeStartActForResult.class")
                add("dev/ide/fakecompose/FakeActivityCallback.class")
                add("dev/ide/fakecompose/FakeActivityResultKt.class") // top-level regFnCallback/regSamCallback facade
            }
            return jar
        }
    }
}
