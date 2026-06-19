package dev.ide.lang.kotlin

import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Lowering against BINARY (`@Metadata`) Compose-shaped classes through the REAL persistent index — the
 * device path, which diverges from the in-memory source/scan path. The fakes (compiled into the test
 * classpath) mirror the shapes the editor preview hit: a scope INTERFACE holding a member extension
 * (`RowScope.weight` ≡ `FakeScope.scopedWeight` on `FakeModifier`), a `@Composable FakeScope.() -> Unit`
 * content slot, and `@Composable inline remember { mutableStateListOf(vararg) }` → a `MutableList` whose
 * `add`/`removeAt` are resolved through generic inference. Regression guard for the scope-extension + vararg
 * + member-extension fixes.
 */
class KotlinComposeBinaryShapeTest {

    private val CLASSES = listOf(
        "androidx/compose/runtime/Composable.class",
        "dev/ide/fakecompose/FakeComposablesKt.class",
        "dev/ide/fakecompose/FakeModifier.class",
        "dev/ide/fakecompose/FakeModifier\$Companion.class",
        "dev/ide/fakecompose/FakeScope.class",
        "dev/ide/fakecompose/FakeScope2.class",
        "dev/ide/fakecompose/FakeState.class",
        "dev/ide/fakecompose/FakeList.class",
        "dev/ide/fakecompose/FakeItemScope.class",
        "dev/ide/fakecompose/FakeListScope.class",
        "dev/ide/fakecompose/FakeModifierKt.class",
        "dev/ide/fakecompose/FakeDefaults.class",
        "dev/ide/fakecompose/FakeTheme.class",
    )

    private fun jar(): Path {
        val jar = Files.createTempFile("fake-compose", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/fakecompose.kotlin_module")); zos.closeEntry()
            for (name in CLASSES) {
                val bytes = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() } ?: continue
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
        }
        return jar
    }

    private val fakeJar = jar()
    private val index = IndexServiceImpl(
        listOf(KotlinTypeShapeIndex, KotlinCallableIndex),
        cacheRoot = Files.createTempDirectory("idx"),
    ).also { runBlocking { it.ensureUpToDate(IndexScope(libraryJars = listOf(fakeJar))) } }
    private val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(fakeJar), index = index)

    private fun lower(code: String): dev.ide.lang.kotlin.interp.ResolvedFunction {
        val kt = KotlinParserHost.parse("Use.kt", "import dev.ide.fakecompose.*\nimport androidx.compose.runtime.*\n$code")
        val parsed = KotlinParsedFile(kt, FakeFile("Use.kt"), 0)
        return KotlinTreeResolver(kt, parsed, service).lowerFirstFunction()!!
    }

    private fun assertComplete(fn: dev.ide.lang.kotlin.interp.ResolvedFunction) {
        val unsupported = ArrayList<String>()
        fn.body.walk { if (it is RNode.Unsupported) unsupported += "${it.reason}: ${it.text}" }
        assertTrue(unsupported.isEmpty(), "expected no unsupported nodes; got $unsupported")
    }

    @Test
    fun classMemberExtensionsAreKeptInTheBinaryTypeShape() {
        // The shape of a scope INTERFACE must retain its member extensions (with their extension receiver),
        // so an implicit-receiver-driven lookup can apply them — they were previously dropped on the binary path.
        val members = service.membersOf("dev.ide.fakecompose.FakeScope", emptyList(), null)
            .filterIsInstance<KotlinSymbol>()
        assertTrue(members.any { it.name == "scopedWeight" && it.receiverTypeFqn == "dev.ide.fakecompose.FakeModifier" },
            "FakeScope's member extension must be in its shape; got ${members.map { it.name }}")
    }

    @Test
    fun scopeMemberExtensionResolvesInsideAContentLambda() {
        // `FakeRow { FakeModifier.scopedWeight(1) }` — the `Row { Modifier.weight(1f) }` shape over binaries.
        val fn = lower("fun f() { FakeRow { FakeModifier.scopedWeight(1) } }")
        assertComplete(fn)
        // It must lower to a MEMBER_EXTENSION dispatched on the scope (the content lambda's receiver), not a
        // plain static extension — otherwise it can't EXECUTE (no `RowScope` instance to invoke `weight` on).
        var memberExt = 0
        fn.body.walk { if (it is RNode.Call && it.callee.displayName == "scopedWeight") {
            assertEquals(dev.ide.lang.kotlin.interp.DispatchKind.MEMBER_EXTENSION, it.dispatch, "scopedWeight should dispatch on its scope")
            assertTrue(it.dispatchReceiver != null, "MEMBER_EXTENSION must carry the scope instance")
            memberExt++
        } }
        assertEquals(1, memberExt, "the scopedWeight call should lower")
    }

    @Test
    fun receiverLambdaWithValueParamsBindsParamsPastTheReceiver() {
        // `fakeItemsIndexed(xs) { index, item -> }` — `itemContent` is `FakeItemScope.(Int, T) -> Unit`, so the
        // runtime passes `[scope, index, item]`. The lowered lambda must be `[<this>, index, item]` (receiver
        // FIRST), or the explicit params shift by one (`item` would bind to the index → the `Text(todo)` got an
        // Integer bug). Verifies the implicit receiver is prepended even when value params are declared.
        val fn = lower("fun f(xs: List<String>) { fakeLazyColumn { fakeItemsIndexed(xs) { index, item -> } } }")
        assertComplete(fn)
        // The INNERMOST lambda is itemContent `{ index, item -> }`; the outer is `fakeLazyColumn`'s content.
        val lambdas = ArrayList<RNode.Lambda>()
        fn.body.walk { if (it is RNode.Lambda) lambdas += it }
        val params = assertNotNull(lambdas.lastOrNull(), "the itemContent lambda should lower").params
        assertEquals(3, params.size, "params = [<this>, index, item]; got ${params.map { it.name }}")
        assertEquals("<this>", params[0].name, "the receiver scope must be the FIRST param")
        assertEquals(listOf("index", "item"), params.drop(1).map { it.name }, "the explicit params follow the receiver")
    }

    @Test
    fun uninferableStateFactoryLowersToAnHonestGap() {
        // `val text by fakeRemember { fakeMutableStateOf() }` — `fakeMutableStateOf()` has no argument to pin
        // `T`, no explicit type argument, and no expected type, so its result type can't be inferred (invalid
        // Kotlin). The lowering must surface this as an Unsupported gap naming the type variable, NOT lower a
        // malformed under-applied call (`chooseCallee`'s arity fallback would otherwise pick it) that crashes
        // the run reflectively.
        val fn = lower("@Composable fun C() { val text by fakeRemember { fakeMutableStateOf() } }")
        val gaps = ArrayList<String>()
        fn.body.walk { if (it is RNode.Unsupported) gaps += it.reason }
        assertTrue(
            gaps.any { it.contains("infer type variable T") },
            "uninferable mutableStateOf() should lower to an 'infer type variable T' gap; got $gaps",
        )
    }

    @Test
    fun inferableStateFactoryStillLowersCleanly() {
        // The counterpart: `fakeMutableStateOf("")` HAS an argument that pins `T = String`, so it must lower
        // with no gap (the check must not over-fire on a well-formed generic call).
        assertComplete(lower("@Composable fun C() { val text by fakeRemember { fakeMutableStateOf(\"\") } }"))
    }

    @Test
    fun delegateWithoutGetValueImportLowersToAGap() {
        // `val text by fakeRemember { fakeMutableStateOf("") }` with the factories imported but NOT the
        // `getValue` operator extension: the delegation can't compile, so the preview lowering must surface a
        // gap naming the missing operator instead of silently reading `.value`.
        val kt = KotlinParserHost.parse(
            "Use.kt",
            "import dev.ide.fakecompose.fakeRemember\n" +
                "import dev.ide.fakecompose.fakeMutableStateOf\n" +
                "import androidx.compose.runtime.Composable\n" +
                "@Composable fun C() { val text by fakeRemember { fakeMutableStateOf(\"\") } }",
        )
        val fn = KotlinTreeResolver(kt, KotlinParsedFile(kt, FakeFile("Use.kt"), 0), service).lowerFirstFunction()!!
        val gaps = ArrayList<String>()
        fn.body.walk { if (it is RNode.Unsupported) gaps += it.reason }
        assertTrue(gaps.any { it.contains("getValue") }, "a delegate missing its getValue import should gap; got $gaps")
    }

    @Test
    fun rememberOfVarargFactoryInfersTheCollectionType() {
        // `val l = remember { mutableStateListOf("a","b") }; l.add("c"); l.removeAt(0)` — vararg-through-generic
        // inference must type `l` as the `MutableList` so its members resolve.
        assertComplete(
            lower("fun f() { val l = fakeRemember { fakeMutableStateListOf(\"a\", \"b\") }\n l.add(\"c\")\n l.removeAt(0) }"),
        )
    }
}
