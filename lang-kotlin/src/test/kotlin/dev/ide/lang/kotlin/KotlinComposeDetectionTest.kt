package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinMetadata
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Binary `@Composable` detection (milestone 6, classpath provisioning). The real IDE feeds the project's
 * Compose AAR jars to the symbol service; here a fake `androidx.compose.runtime.Composable` compiled into the
 * test classpath stands in (no Compose toolchain), so the bytecode-detection path is verified end to end in
 * CI: a provisioned jar → the resolver resolves the composable to a [ResolvedCallable.Library] with
 * `isComposable` set and a precise `…Kt` facade owner.
 */
class KotlinComposeDetectionTest {

    @Test
    fun decodeDetectsComposableFromBytecode() {
        val decoded = assertNotNull(KotlinMetadata.decode(classBytes(FACADE), null), "facade should decode")
        val fakeText = assertNotNull(decoded.topLevel.firstOrNull { it.name == "FakeText" }, "FakeText present")
        assertTrue(fakeText.isComposable, "@Composable function should be detected from bytecode")
        val plain = assertNotNull(decoded.topLevel.firstOrNull { it.name == "plainHelper" }, "plainHelper present")
        assertFalse(plain.isComposable, "a plain function must not be flagged composable")
    }

    @Test
    fun decodeDetectsComposableFunctionTypeParam() {
        val decoded = assertNotNull(KotlinMetadata.decode(classBytes(FACADE), null), "facade should decode")
        val column = assertNotNull(decoded.topLevel.firstOrNull { it.name == "FakeColumn" }, "FakeColumn present")
        val content = assertIs<KotlinType>(column.paramTypes.first(), "content param type decoded")
        assertTrue(content.isComposable, "a `@Composable () -> Unit` param type must be flagged composable")

        val forEach = assertNotNull(decoded.topLevel.firstOrNull { it.name == "fakeForEach" }, "fakeForEach present")
        val block = assertIs<KotlinType>(forEach.paramTypes.first(), "block param type decoded")
        assertFalse(block.isComposable, "a plain function-type param must not be flagged composable")
    }

    @Test
    fun resolverResolvesProvisionedBinaryComposable() {
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(buildFakeComposeJar()))
        val code = "fun f() { FakeText(\"x\") }"
        val kt = KotlinParserHost.parse("Use.kt", code)
        val parsed = KotlinParsedFile(kt, FakeFile("Use.kt"), 0)
        val fn = assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction())
        val call = assertIs<RNode.Call>(assertIs<RNode.Block>(fn.body).statements[0])
        val lib = assertIs<ResolvedCallable.Library>(call.callee)
        assertEquals("FakeText", lib.displayName)
        assertTrue(lib.isComposable, "resolved binary composable should carry isComposable")
        assertTrue(lib.descriptorPrecise, "owner should be precise")
        assertEquals("dev.ide.fakecompose.FakeComposablesKt", lib.ownerFqn)
    }

    @Test
    fun overloadsDifferingOnlyInADefaultedParamCollapseForThisCall() {
        // `fakeChip(onClick = {}, label = {})` — two `fakeChip` overloads differ only in a defaulted param the
        // call doesn't supply (the Material3 `SuggestionChip` shape). The supplied args bind to identical
        // params in both, so the call site can't distinguish them: it must resolve to ONE composable, not stay
        // ambiguous (which made the enclosing function Unsupported → "function X has unsupported nodes").
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(buildFakeComposeJar()))
        val code = "fun f() { fakeChip(onClick = {}, label = {}) }"
        val kt = KotlinParserHost.parse("Use.kt", code)
        val parsed = KotlinParsedFile(kt, FakeFile("Use.kt"), 0)
        val fn = assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction())
        assertTrue(fn.isComplete, "indistinguishable defaulted-param overloads should collapse; diags=${fn.diagnostics}")
        val call = assertIs<RNode.Call>(assertIs<RNode.Block>(fn.body).statements[0])
        val lib = assertIs<ResolvedCallable.Library>(call.callee)
        assertEquals("fakeChip", lib.displayName)
        assertTrue(lib.isComposable, "the resolved overload should still be composable")
    }

    // --- helpers ---

    private fun classBytes(path: String): ByteArray =
        assertNotNull(javaClass.classLoader.getResourceAsStream(path), "missing class resource $path").use { it.readBytes() }

    /** Stage the fake-compose classes into a jar the symbol service can scan (a `kotlin_module` entry makes
     *  it look like a Kotlin library so the scan doesn't skip it). */
    private fun buildFakeComposeJar(): Path {
        val jar = Files.createTempFile("fake-compose", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            fun add(name: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            add("META-INF/fakecompose.kotlin_module", ByteArray(0))
            add("androidx/compose/runtime/Composable.class", classBytes("androidx/compose/runtime/Composable.class"))
            add(FACADE, classBytes(FACADE))
        }
        return jar
    }

    private companion object {
        const val FACADE = "dev/ide/fakecompose/FakeComposablesKt.class"
    }
}
