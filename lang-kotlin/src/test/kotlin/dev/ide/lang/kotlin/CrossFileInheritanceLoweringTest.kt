package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The preview lowerer must resolve source members / inherited members whose declaration lives in ANOTHER file.
 * The source-vs-library recovery (`sourceClassOfReceiver`) and the inherited-member walks
 * (`acceptsSourceMember` / the class-context build) were gated on the current-file-only index, so a data class
 * defined elsewhere, or a base class in a sibling file, fell to `Unsupported`/reflective and blanked the
 * preview. They now consult the whole-project source registry (`service.sourceClass`) when the file-local
 * index misses.
 */
class CrossFileInheritanceLoweringTest {

    private fun serviceOver(files: Map<String, String>): Pair<KotlinSymbolService, Path> {
        val dir = tempProject(files)
        return KotlinSymbolService(listOf(DiskFile(dir)), listOf(stdlibJarPath())) to dir
    }

    private fun lowerFirstFn(service: KotlinSymbolService, dir: Path, file: String, text: String): ResolvedFunction {
        val kt = KotlinParserHost.parse(file, text)
        val parsed = KotlinParsedFile(kt, DiskFile(dir.resolve(file)), 0)
        return assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction())
    }

    private fun callNamed(fn: ResolvedFunction, name: String): RNode.Call {
        var call: RNode.Call? = null
        fn.body.walk { if (it is RNode.Call && it.callee.displayName == name) call = it }
        return assertNotNull(call, "`$name(...)` must lower to a Call; diags=${fn.diagnostics.map { it.reason }}")
    }

    @Test
    fun dataClassCopyOnACrossFileReceiverLowersToSourceMember() {
        // `u.copy(name = "x")` where `data class User` lives in another file. The editor resolver doesn't surface
        // the compiler-SYNTHESIZED `copy`, so the recovery must find it whole-project — a SOURCE member call, not
        // `Unsupported` (which blanks the subtree).
        val use = "package demo\nfun p(u: User) { u.copy(name = \"x\") }\n"
        val (service, dir) = serviceOver(mapOf("Model.kt" to "package demo\ndata class User(val name: String)\n", "Use.kt" to use))
        val fn = lowerFirstFn(service, dir, "Use.kt", use)
        assertTrue(fn.isComplete, "u.copy(...) on a cross-file data class must lower; diags=${fn.diagnostics.map { it.reason }}")
        val call = callNamed(fn, "copy")
        assertTrue(call.callee is ResolvedCallable.Source, "copy must be a SOURCE call, was ${call.callee::class.simpleName}")
        assertTrue(call.dispatch == DispatchKind.MEMBER, "copy must dispatch as a MEMBER, was ${call.dispatch}")
    }

    @Test
    fun memberInheritedFromACrossFileSuperOnACrossFileReceiverResolves() {
        // `card.render()` where `card: HomeCard`, `HomeCard : BaseCard` (a third file), and `render()` is
        // declared on `BaseCard`. Both the receiver type and the member's declaring supertype are cross-file, so
        // the recovery needs `crossFileClassInfo` + the whole-project supertype walk in `acceptsSourceMember`.
        val use = "package demo\nfun p(card: HomeCard): String = card.render()\n"
        val (service, dir) = serviceOver(
            mapOf(
                "Base.kt" to "package demo\nabstract class BaseCard { fun render(): String = \"\" }\n",
                "Home.kt" to "package demo\nclass HomeCard : BaseCard()\n",
                "Use.kt" to use,
            ),
        )
        val fn = lowerFirstFn(service, dir, "Use.kt", use)
        assertTrue(fn.isComplete, "an inherited member call on a cross-file receiver must lower; diags=${fn.diagnostics.map { it.reason }}")
        val call = callNamed(fn, "render")
        assertTrue(call.callee is ResolvedCallable.Source && call.dispatch == DispatchKind.MEMBER,
            "render() must be a SOURCE MEMBER call, was ${call.callee::class.simpleName}/${call.dispatch}")
    }

    @Test
    fun inheritedMembersFromACrossFileSuperclassResolveInASubclassBody() {
        // Inside `HomeCard`'s method bodies, a bare `title` read and `render()` call inherited from a base class
        // in ANOTHER file must resolve — the class-context inherited-member walk must follow the cross-file super.
        val sub = "package demo\nclass HomeCard : BaseCard() {\n  fun show(): String { render(); return title }\n}\n"
        val (service, dir) = serviceOver(
            mapOf(
                "Base.kt" to "package demo\nabstract class BaseCard {\n  val title: String = \"t\"\n  fun render(): String = title\n}\n",
                "Home.kt" to sub,
            ),
        )
        val kt = KotlinParserHost.parse("Home.kt", sub)
        val parsed = KotlinParsedFile(kt, DiskFile(dir.resolve("Home.kt")), 0)
        val classes = KotlinTreeResolver(kt, parsed, service).lowerClasses()
        val home = assertNotNull(classes.firstOrNull { it.simpleName == "HomeCard" }, "HomeCard must lower")
        assertTrue(
            home.isComplete,
            "inherited render()/title from a cross-file superclass must resolve in the subclass body; diags=" +
                home.methods.values.flatMap { it.diagnostics }.map { it.reason },
        )
    }
}
