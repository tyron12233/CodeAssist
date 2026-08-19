package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Static-shaped access through a LOCAL class's name (`L.CONST`, `L.make()`, `L.Nested`) resolves like the
 * same access on a top-level class.
 *
 * A type declared inside a local one has no reachable `fqName`, so it is registered under a synthetic key.
 * That key used to be positional and scoped to the nearest NAMED owner, which for anything inside a local
 * class is the file facade: a local class's companion and its nested types landed as SIBLINGS of the class
 * rather than under it. Nothing could then address them by the name the code uses, since a companion is
 * looked up as `<outer>.Companion` and a nested type probed as `<outer>.<Name>`, so every such access was
 * reported "Unresolved reference".
 */
class KotlinLocalTypeMemberTest {

    private fun diagnose(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun unresolved(code: String) = diagnose(code).filter { it.code == "kt.unresolved" }

    @Test
    fun membersReachedThroughALocalTypeResolve() {
        for (code in listOf(
            "package demo\nfun f() { class L { companion object { val T = 1 } }\n  println(L.T) }",
            "package demo\nfun f() { class L { companion object { const val T = 1 } }\n  println(L.T) }",
            "package demo\nfun f() { class L { companion object { fun make() = 1 } }\n  println(L.make()) }",
            "package demo\nfun f() { class L { object N { val T = 1 } }\n  println(L.N.T) }",
            "package demo\nfun f() { class L { class Inner { val v = 1 } }\n  println(L.Inner().v) }",
            // A named companion, and one nested two deep.
            "package demo\nfun f() { class L { companion object Named { val T = 1 } }\n  println(L.T) }",
            "package demo\nfun f() { class L { object N { object M { val T = 1 } } }\n  println(L.N.M.T) }",
        )) {
            val d = unresolved(code)
            assertTrue(d.isEmpty(), "a member of a local type must resolve; got ${d.map { it.message }} for:\n$code")
        }
    }

    @Test
    fun theShapesThatAlreadyWorkedStillDo() {
        for (code in listOf(
            "package demo\nfun f() { object O { val T = 1 }\n  println(O.T) }",          // local object
            "package demo\nfun f() { class L { val v = 1 }\n  println(L().v) }",          // instance member
            "package demo\nfun f() { enum class E { A }\n  println(E.A) }",               // local enum entry
            "package demo\nclass TL { companion object { val T = 1 } }\nfun f() { println(TL.T) }",
            "package demo\nobject TO { val T = 1 }\nfun f() { println(TO.T) }",
            // An anonymous object still keys positionally, and its members still resolve.
            "package demo\nfun f() { val o = object { val v = 1 }\n  println(o.v) }",
        )) {
            val d = unresolved(code)
            assertTrue(d.isEmpty(), "got ${d.map { it.message }} for:\n$code")
        }
    }

    @Test
    fun anUnknownMemberOfALocalTypeIsStillReported() {
        for ((name, code) in listOf(
            "NOPE" to "package demo\nfun f() { class L { companion object { val T = 1 } }\n  println(L.NOPE) }",
            "NOPE" to "package demo\nfun f() { object O { val T = 1 }\n  println(O.NOPE) }",
            "NOPE" to "package demo\nfun f() { class L { object N { val T = 1 } }\n  println(L.N.NOPE) }",
        )) {
            val d = unresolved(code)
            assertTrue(
                d.any { it.message.contains("Unresolved reference: $name") },
                "a genuinely missing member must still be flagged; got ${d.map { it.message }} for:\n$code",
            )
        }
    }

    @Test
    fun twoLocalClassesInOneFileKeepDistinctMembers() {
        // The keys must stay unique per local type: same member name, different owners, both resolvable and
        // neither leaking into the other.
        val d = unresolved(
            "package demo\n" +
                "fun a() { class L { companion object { val T = 1 } }\n  println(L.T) }\n" +
                "fun b() { class L { companion object { val U = 2 } }\n  println(L.U) }\n"
        )
        assertTrue(d.isEmpty(), "two local classes must not collide; got ${d.map { it.message }}")

        val crossed = unresolved(
            "package demo\n" +
                "fun a() { class L { companion object { val T = 1 } }\n  println(L.T) }\n" +
                "fun b() { class L { companion object { val U = 2 } }\n  println(L.T) }\n"
        )
        assertTrue(
            crossed.any { it.message.contains("Unresolved reference: T") },
            "the second L has no T, so it must be flagged; got ${crossed.map { it.message }}",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\nfun seed() {}"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
