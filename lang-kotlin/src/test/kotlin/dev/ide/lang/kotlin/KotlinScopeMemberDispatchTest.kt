package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression for `LazyListScope.item$default(…)` NPE (`<parameter1>` null): inside `LazyColumn { item { } }`,
 * `item`/`items(count, …)` are MEMBERS of the `LazyListScope` interface the content lambda provides — NOT
 * extensions. A bare call to such a member used to fall through to a TOP_LEVEL dispatch with a null receiver,
 * so the interpreter invoked the member's static `$default` synthetic with a null `$this` scope → NPE. It now
 * dispatches MEMBER on the in-scope implicit receiver. Exercised with the binary fake `FakeListScope.fakeItem`
 * (a defaulted-param + composable-content interface member, the `item` shape) compiled into the test classpath.
 */
class KotlinScopeMemberDispatchTest {

    private fun classBytes(path: String): ByteArray =
        assertNotNull(javaClass.classLoader.getResourceAsStream(path), "missing class resource $path").use { it.readBytes() }

    private fun jar(): Path {
        val jar = Files.createTempFile("fake-lazy", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/fakecompose.kotlin_module")); zos.closeEntry()
            fun add(name: String) { zos.putNextEntry(ZipEntry(name)); zos.write(classBytes(name)); zos.closeEntry() }
            add("androidx/compose/runtime/Composable.class")
            add("dev/ide/fakecompose/FakeComposablesKt.class")
            add("dev/ide/fakecompose/FakeListScope.class")
            add("dev/ide/fakecompose/FakeItemScope.class")
        }
        return jar
    }

    @Test
    fun scopeMemberCalledBareDispatchesOnTheImplicitReceiver() {
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(jar()))
        val code = "fun f() { fakeLazyColumn { fakeItem { } } }"
        val kt = KotlinParserHost.parse("Use.kt", code)
        val parsed = KotlinParsedFile(kt, FakeFile("Use.kt"), 0)
        val fn = assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction())
        assertTrue(fn.isComplete, "the scoped member call should lower completely; diags=${fn.diagnostics}")

        var item: RNode.Call? = null
        fn.body.walk { if (it is RNode.Call && it.callee.displayName == "fakeItem") item = it }
        val call = assertNotNull(item, "the `fakeItem` member call should lower to a Call")
        assertEquals(DispatchKind.MEMBER, call.dispatch, "a bare scope-member call must dispatch on the scope, not TOP_LEVEL")
        // The receiver must be the content lambda's implicit `<this>` scope (slot-bound), never null.
        val recv = assertIs<RNode.Name>(call.receiver, "the member call must carry the scope as its receiver")
        assertIs<Binding.Local>(recv.binding)
    }
}
