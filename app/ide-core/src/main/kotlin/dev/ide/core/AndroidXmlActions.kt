package dev.ide.core

import dev.ide.analysis.ActionProvider
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.xml.XmlLanguageBackend
import dev.ide.lang.xml.lint.XmlLintRules

/**
 * The Android XML caret intentions (the `platform.actionProvider` contribution for `.xml`). Today: convert an
 * AppCompat-backed framework attribute to its `app:` compat form — `android:src` → `app:srcCompat`,
 * `android:drawableStart` → `app:drawableStartCompat`, … — declaring `xmlns:app` on the root when absent.
 *
 * It's an intention (offered via the lightbulb, not a squiggle) and is gated by [appCompatAvailable] so it
 * never appears where the `app:` form wouldn't resolve (no AppCompat on the classpath). Pure over the DOM +
 * that predicate, so it unit-tests without a project.
 */
class AndroidXmlActionProvider(
    private val appCompatAvailable: (AnalysisTarget) -> Boolean,
) : ActionProvider {

    override val languages = setOf(XmlLanguageBackend.LANGUAGE_ID)

    override fun actions(target: AnalysisTarget, range: TextRange): List<QuickFix> {
        val caret = range.start
        val tags = runCatching { XmlLintRules.allTags(target.parsed) }.getOrDefault(emptyList())
        for (tag in tags) for (attr in tag.attributes) {
            val name = attr.name ?: continue
            if (caret < attr.startOffset || caret > attr.endOffset) continue // caret must be on this attribute
            if (!name.startsWith("android:")) continue
            val to = MIGRATIONS[name.removePrefix("android:")] ?: continue
            if (!appCompatAvailable(target)) return emptyList()
            return listOf(convertFix(name, "app:$to", attr.startOffset, name.length, target.parsed))
        }
        return emptyList()
    }

    private fun convertFix(from: String, to: String, nameStart: Int, nameLen: Int, parsed: ParsedFile): QuickFix =
        object : QuickFix {
            override val title = "Convert '$from' to '$to'"
            override val kind = CodeActionKind.INTENTION
            override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
                val edits = ArrayList<DocumentEdit>()
                edits += DocumentEdit(nameStart, nameLen, to)        // rename the attribute prefix/local name
                appNamespaceEdit(parsed)?.let { edits += it }        // …and declare xmlns:app if it's missing
                return WorkspaceEdit(mapOf(ctx.target.file to edits))
            }
        }

    /** The edit declaring `xmlns:app` on the root element when absent, else null. */
    private fun appNamespaceEdit(parsed: ParsedFile): DocumentEdit? {
        val root = XmlLintRules.allTags(parsed).firstOrNull() ?: return null
        if (root.attributes.any { it.name == "xmlns:app" }) return null
        val at = root.startOffset + 1 + (root.name?.length ?: 0)
        return DocumentEdit(at, 0, " xmlns:app=\"http://schemas.android.com/apk/res-auto\"")
    }

    companion object {
        /** AppCompat compat-attribute migrations: `android:<from>` → `app:<to>` (AppCompat reads the `app:` form). */
        val MIGRATIONS: Map<String, String> = mapOf(
            "src" to "srcCompat",
            "tint" to "tint",
            "tintMode" to "tintMode",
            "drawableLeft" to "drawableLeftCompat",
            "drawableRight" to "drawableRightCompat",
            "drawableStart" to "drawableStartCompat",
            "drawableEnd" to "drawableEndCompat",
            "drawableTop" to "drawableTopCompat",
            "drawableBottom" to "drawableBottomCompat",
        )
    }
}
