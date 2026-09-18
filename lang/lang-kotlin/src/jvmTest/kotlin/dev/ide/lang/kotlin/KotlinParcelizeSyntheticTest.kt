package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The editor view onto kotlin-parcelize's compiler-plugin-generated members: a `@Parcelize` class gets a
 * `@JvmField val CREATOR: Parcelable.Creator<T>` the parse-only model never sees (it isn't declared in source
 * and isn't inherited from `Parcelable`). [ParcelizeSyntheticMembers] (contributed on
 * `platform.kotlinSyntheticMember`, the default in direct wiring) makes `Foo.CREATOR` complete on `Foo.`,
 * resolve (its `Parcelable.Creator<Foo>` chain enumerates `createFromParcel`/`newArray`), and NOT false-flag
 * `kt.unresolved` — while a module WITHOUT `android.os.Parcelable` synthesizes nothing (the runtime gate).
 *
 * Deterministic: `android.os.Parcelable` and `@Parcelize` are source stubs, so no android.jar is needed.
 */
class KotlinParcelizeSyntheticTest {

    private fun analyzer(withRuntime: Boolean): Pair<KotlinSourceAnalyzer, Path> {
        val files = buildMap {
            put("Parcelize.kt", "package kotlinx.parcelize\nannotation class Parcelize\n")
            if (withRuntime) put(
                "Parcelable.kt",
                """
                package android.os
                interface Parcel
                interface Parcelable {
                    interface Creator<T> { fun createFromParcel(source: Parcel): T; fun newArray(size: Int): Array<T?> }
                    fun writeToParcel(dest: Parcel, flags: Int)
                    fun describeContents(): Int
                }
                """.trimIndent() + "\n",
            )
        }
        val srcDir = tempProject(files)
        return KotlinSourceAnalyzer(fakeContext(srcDir, listOf(stdlibJarPath()))) to srcDir
    }

    private val model = """
        package demo
        import android.os.Parcelable
        import kotlinx.parcelize.Parcelize
        @Parcelize class Foo(val x: Int) : Parcelable
    """.trimIndent() + "\n"

    private fun completionNames(withRuntime: Boolean, body: String): List<String> {
        val (a, srcDir) = analyzer(withRuntime)
        val code = model + "fun use() { $body }\n"
        return runBlocking { a.completeAtCaret(srcDir, "Foo.kt", code) }.items.mapNotNull { it.symbol?.name }
    }

    @Test
    fun creatorCompletesOnType() {
        val items = completionNames(withRuntime = true, body = "Foo.|")
        assertTrue("CREATOR" in items, "`Foo.` should offer the synthesized `CREATOR`; got $items")
    }

    @Test
    fun creatorChainResolves() {
        // `Foo.CREATOR` is typed `Parcelable.Creator<Foo>` — a chain off it enumerates the interface's members.
        val items = completionNames(withRuntime = true, body = "Foo.CREATOR.|")
        assertTrue("createFromParcel" in items, "`Foo.CREATOR.` should offer `Parcelable.Creator` members; got $items")
    }

    @Test
    fun creatorIsNotUnresolved() {
        val (a, srcDir) = analyzer(withRuntime = true)
        val code = model + "fun use() { val c = Foo.CREATOR }\n"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Foo.kt")))
        val diags: List<Diagnostic> = runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file).diagnostics }
        val unresolved = diags.filter { it.code == KotlinDiagnosticCodes.UNRESOLVED && it.message.contains("CREATOR") }
        assertTrue(unresolved.isEmpty(), "`Foo.CREATOR` must not be flagged unresolved; got $unresolved")
    }

    @Test
    fun notSynthesizedWithoutRuntime() {
        // No android.os.Parcelable on the classpath ⇒ the provider's gate fails ⇒ nothing synthesized.
        val items = completionNames(withRuntime = false, body = "Foo.|")
        assertFalse("CREATOR" in items, "without android.os.Parcelable, `CREATOR` must NOT be synthesized; got $items")
    }
}
