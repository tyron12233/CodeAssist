// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.lang.kotlin.parity

import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.collectDescendantsOfType
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.completion.complete
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.IncrementalSemanticAnalysis
import dev.ide.lang.kotlin.KotlinEditorFeatures
import dev.ide.lang.kotlin.KotlinLanguage
import dev.ide.lang.kotlin.completion.KotlinCompletion
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.SourceIndexBuilder
import dev.ide.lang.resolve.ResolveResult
import dev.ide.vfs.VirtualFile

/**
 * A deterministic textual rendering of what the Kotlin front end computed for one file.
 *
 * It exists so that "the analysis behaves the same on Android as on the JVM" can be a TEST rather than an
 * assumption. The JVM suite records a digest of a fixed corpus into a committed golden; the instrumented
 * suite recomputes it on ART and compares. Both run THIS code, which is why it lives in `commonMain` and
 * not in either test source set -- two copies of a digest prove that two copies agree.
 *
 * **What divergence would even look like.** The two platforms run the same bytecode, so a difference is
 * never "the parser is different". It is one of three things, and each is real and has shipped before:
 * a JDK method ART does not have (`NoSuchMethodError`, or a swallowed one inside `runCatching` that turns
 * into a quietly wrong answer), a collection whose iteration order differs between the two `HashMap`
 * implementations (which reorders completion), or a locale-sensitive comparison (which reorders anything
 * sorted by name). All three show up here as a diff.
 *
 * **Nothing in here may sort what the engine did not sort.** The order of a completion list or a symbol
 * enumeration IS the output under test: normalising it would hide exactly the divergence this is for.
 */
object KotlinAnalysisDigest {

    /** Bumped when the rendering changes, so a stale golden fails loudly instead of diffing as content. */
    const val FORMAT: String = "kt-digest-1"

    /**
     * The syntax half: parse tree, the parser's own errors, and the outline.
     *
     * No classpath and no symbol model, so it runs over any file at all -- including the deliberately
     * broken ones in a parser's test corpus, which is most of what makes it worth running.
     */
    fun syntax(name: String, text: CharSequence): String = buildString {
        val file = KotlinParserHost.parse(name, text)
        appendLine("file $name")
        appendLine("  package ${file.packageFqName.asString().ifEmpty { "<root>" }}")
        for (directive in file.importDirectives) {
            appendLine("  import ${directive.importedFqName?.asString() ?: "<?>"}${directive.aliasName?.let { " as $it" } ?: ""}")
        }
        appendLine("  tree")
        appendTree(file, depth = 2)
    }

    /**
     * The tree, as `type[start..end]` per composite node.
     *
     * Token-like nodes are folded away (they carry no structure, and there are an order of magnitude more
     * of them), but their TEXT is appended to the parent's line when the parent is a leaf-ish composite,
     * so a difference in what was lexed still shows. Offsets are included because a shifted range is a real
     * difference that a shape-only rendering would miss.
     */
    private fun StringBuilder.appendTree(element: KtElement, depth: Int) {
        val range = element.textRange
        append(" ".repeat(depth * 2))
        append(element.elementType.toString())
        append('[').append(range.startOffset).append("..").append(range.endOffset).append(']')
        val structural = element.children.filterNot { it.isTokenLike }
        if (structural.isEmpty() && !element.isToken) {
            val text = element.text.trim()
            if (text.isNotEmpty() && text.length <= 40) append(" '").append(text.replace("\n", "\\n")).append('\'')
        }
        appendLine()
        for (child in structural) appendTree(child, depth + 1)
    }

    /**
     * The semantic half: diagnostics, what every name reference resolves to, and what completion offers
     * after every `.`.
     *
     * This is the part where the two platforms can genuinely differ, because it is the part that enumerates
     * from hash-keyed collections and sorts by name. The completion list is recorded IN ORDER and capped
     * rather than sorted here: its order is the product, and a reordering on ART is precisely the bug this
     * is looking for.
     *
     * [service] must be built over the same sources and the same classpath on both sides, or the diff is
     * about the fixture rather than the platform.
     */
    suspend fun semantic(file: VirtualFile, text: CharSequence, service: KotlinSymbolService): String = buildString {
        val parsed = KotlinParsedFile(KotlinParserHost.parse(file.name, text), file, 0L)
        val analysis = IncrementalSemanticAnalysis(service)
        // The focal sync is not optional here. Without it the symbol model knows the file only as it is on
        // disk, so nothing declared in the buffer under analysis resolves -- and a digest in which half the
        // references read `<unresolved>` would agree across platforms while proving nothing about either.
        val syncFocal = {
            service.syncFocal(file.path, parsed.ktFile.text.hashCode()) {
                SourceIndexBuilder.extractFrom(parsed.ktFile, parsed, file.path)
            }
            Unit
        }
        val features = KotlinEditorFeatures(
            service, analysis, parsedFor = { parsed }, syncFocal = { syncFocal() },
        )
        val completion = KotlinCompletion(service) { syncFocal() }
        syncFocal()

        appendLine("file ${file.name}")

        appendLine("  diagnostics")
        for (d in analysis.diagnostics(parsed)) {
            appendLine("    ${d.severity} ${d.code ?: "-"} [${d.range.start}..${d.range.end}] ${d.message}")
        }

        appendLine("  references")
        for (reference in parsed.ktFile.collectDescendantsOfType<KtNameReferenceExpression>()) {
            val offset = reference.textRange.startOffset
            val resolved = when (val r = features.resolve(parsed.nodeAt(offset))) {
                is ResolveResult.Resolved -> "${r.symbol.kind} ${r.symbol.name}"
                else -> "<unresolved>"
            }
            appendLine("    [$offset] ${reference.getReferencedName()} -> $resolved")
        }

        appendLine("  completion")
        for (qualified in parsed.ktFile.collectDescendantsOfType<KtQualifiedExpression>()) {
            val selector = qualified.selectorExpression ?: continue
            val offset = selector.textRange.startOffset
            val items = completion.complete(
                CompletionRequest(DigestDocument(text.toString(), file), offset, CompletionTrigger.Explicit),
                KotlinLanguage.ID,
            ).items
            // Capped: a member list on a library type runs to hundreds, and the first few are what ranking
            // is about. The COUNT is recorded too, so a change in what was offered still shows.
            appendLine("    [$offset] ${items.size} items: ${items.take(12).joinToString(", ") { it.label }}")
        }
    }
}

/** The buffer a digest completes against: the whole file, unversioned, because nothing here edits. */
private class DigestDocument(private val content: String, override val file: VirtualFile) : DocumentSnapshot {
    override val version: Long = 1
    override val text: CharSequence get() = content
    override fun length(): Int = content.length
}
