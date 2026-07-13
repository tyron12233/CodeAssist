package dev.ide.lang.kotlin.parse

import dev.ide.psi.IntellijPsiHost
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile

/**
 * The resolution-free Kotlin PSI host: `text -> KtFile`.
 *
 * The heavy lifting — standing up the shared IntelliJ platform environment, the coarse parse lock, and the
 * `forceFullParse` tree materializer — now lives in [IntellijPsiHost] (module `:intellij-psi-host`), so the
 * XML backend can register its own language onto the SAME single application environment. This object is the
 * thin Kotlin-specific layer over it: it creates `KtFile`s and drives Kotlin incremental reparse.
 *
 * It never builds a `BindingContext` or runs the analyzer/FIR: all semantic work (symbols, resolution,
 * inference, completion) is done on the neutral DOM. The PSI parser is error-tolerant: it emits
 * `PsiErrorElement`s and recovers on broken input, so a `KtFile` always covers the whole file, satisfying the
 * DOM's error-tolerance contract (essential because completion fires mid-edit).
 *
 * Threading: [parse]/[tryReparse] serialize under the shared parse lock ([IntellijPsiHost.withParseLock]) and
 * fully materialize the tree ([IntellijPsiHost.forceFullParse]) while holding it, so no `buildTree` ever runs
 * during the unlocked, possibly-concurrent traversal that follows — a native SIGSEGV on ART otherwise (see
 * [IntellijPsiHost]).
 */
object KotlinParserHost {

    /**
     * Parse [text] into a [KtFile] named [name]. Never throws on syntactically invalid input — broken regions
     * become `PsiErrorElement`s in the returned tree. [name] should end in `.kt`/`.kts` so PSI picks the
     * Kotlin file type; it need not correspond to a real file.
     */
    fun parse(name: String, text: CharSequence): KtFile {
        val fileName = if (name.endsWith(".kt") || name.endsWith(".kts")) name else "$name.kt"
        return IntellijPsiHost.parse(fileName, KotlinLanguage.INSTANCE, text) as KtFile
    }

    /**
     * Incrementally reparse [file] in place so its text becomes [newText], reusing the unchanged subtrees of
     * the existing PSI (only the changed span is re-lexed/re-parsed). Returns the same (now-mutated) [file] on
     * success, or null when incremental reparse isn't applicable / failed — the caller then [parse]s fresh
     * (a failed reparse may leave [file] partially mutated, so it must be discarded). Serialized under the
     * same parse lock as [parse], so it never races a parse on a background index thread.
     */
    fun tryReparse(file: KtFile, newText: CharSequence): KtFile? =
        IntellijPsiHost.withParseLock {
            KotlinPsiMutation.reparse(file, newText)?.also { IntellijPsiHost.forceFullParse(it) }
        }

    /** Force the (expensive) environment up now — call off the UI thread at startup to hide cold-start. */
    fun warmUp() = IntellijPsiHost.warmUp()
}
