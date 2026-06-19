package dev.ide.lang.kotlin.index

import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.SourceDocExternalizer
import dev.ide.index.SourceDocValue
import dev.ide.index.StringKeyDescriptor
import dev.ide.lang.kotlin.parse.KotlinParserHost
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSecondaryConstructor

/**
 * sourceDoc (Kotlin): owner class FQN -> per-member-function real parameter NAMES + cleaned KDoc, recovered
 * from an attached Kotlin `-sources.jar`. Kotlin `@Metadata` already carries parameter names, so the win here
 * is the KDoc text the editor's doc panel shows; names are filled too for parity. `.kt` sources only; a
 * resolution-free PSI parse via the standalone [KotlinParserHost]. A class's own KDoc is the empty-name entry.
 *
 * Members of classes/objects only (keyed by the declaring class FQN, matching a binary member symbol's
 * `declaringClassFqn`). Top-level/extension callables — keyed by the `<File>Kt` facade — are not indexed yet.
 */
object KotlinSourceDocIndex : IndexExtension<String, SourceDocValue> {
    override val id = IndexId("kotlin.sourceDoc")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = SourceDocExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = InputFilter { it.origin == IndexOrigin.LIBRARY_SOURCE && it.unitName?.endsWith(".kt") == true }

    override fun index(input: IndexInput): Map<String, Collection<SourceDocValue>> {
        val text = input.text() ?: return emptyMap()
        val kt = runCatching { KotlinParserHost.parse(input.unitName ?: "Source.kt", text) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, MutableList<SourceDocValue>>()
        kt.declarations.filterIsInstance<KtClassOrObject>().forEach { collect(it, out) }
        return out
    }

    private fun collect(c: KtClassOrObject, out: MutableMap<String, MutableList<SourceDocValue>>) {
        val fqn = c.fqName?.asString() ?: return
        val simple = fqn.substringAfterLast('.')
        val bucket = out.getOrPut(fqn) { ArrayList() }
        docOf(c)?.let { bucket.add(SourceDocValue("", -1, emptyList(), it)) }
        c.primaryConstructor?.let { pc ->
            val names = pc.valueParameters.map { it.name ?: "_" }
            bucket.add(SourceDocValue(simple, names.size, names, docOf(c) ?: docOf(pc)))
        }
        for (d in c.declarations) {
            when (d) {
                is KtNamedFunction -> {
                    val names = d.valueParameters.map { it.name ?: "_" }
                    bucket.add(SourceDocValue(d.name ?: continue, names.size, names, docOf(d)))
                }
                is KtSecondaryConstructor -> {
                    val names = d.valueParameters.map { it.name ?: "_" }
                    bucket.add(SourceDocValue(simple, names.size, names, docOf(d)))
                }
                is KtClassOrObject -> collect(d, out) // nested type → its own FQN bucket
                else -> {}
            }
        }
    }

    private fun docOf(d: KtDeclaration): String? =
        d.docComment?.text?.let { cleanKDoc(it) }?.takeIf { it.isNotEmpty() }

    /** Strip the `/** … */` markers and `@tag` lines, keep paragraph breaks, cap the length. */
    private fun cleanKDoc(raw: String): String =
        raw.lineSequence()
            .map { it.trim().removePrefix("/**").removePrefix("/*").let { l -> if (l.endsWith("*/")) l.dropLast(2) else l }.trim().removePrefix("*").trim() }
            .filterNot { it.startsWith("@") }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(2000)
}
