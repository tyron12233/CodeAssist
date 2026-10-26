package dev.ide.core.analysis

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.QuickFixProvider
import dev.ide.analysis.WorkspaceEdit
import dev.ide.analysis.Diagnostic
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.resolve.PermissionGuards
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile

/**
 * The missing-permission check's manifest fix, for Java and Kotlin alike: add the `<uses-permission>` the call
 * needs to the module's `AndroidManifest.xml`. An any-of requirement (`ACCESS_COARSE_LOCATION or
 * ACCESS_FINE_LOCATION`) gets one fix per permission, since the choice is the developer's; an all-of one gets a
 * single fix adding every missing permission. Host-level because only the host knows where a module's manifest is.
 */
class ManifestPermissionFixProvider(private val manifestOf: (Module) -> VirtualFile?) : QuickFixProvider {
    override val forCodes = setOf(PermissionGuards.MISSING_PERMISSION)

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        if (!PermissionGuards.isMissingFromManifest(diagnostic.message)) return emptyList()
        val (permissions, anyOf) = PermissionGuards.permissionsNamedIn(diagnostic.message)
        if (permissions.isEmpty()) return emptyList()
        val module = target.module
        val choices = if (anyOf) permissions.map { listOf(it) } else listOf(permissions)
        return choices.map { add ->
            object : QuickFix {
                override val title = "Add ${add.joinToString(", ") { it.substringAfterLast('.') }} permission to AndroidManifest.xml"
                override val kind = CodeActionKind.QUICK_FIX
                override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
                    val manifest = manifestOf(module)?.takeIf { it.exists } ?: return WorkspaceEdit.EMPTY
                    val edit = ManifestPermissionEdits.insertion(manifest.readText().toString(), add) ?: return WorkspaceEdit.EMPTY
                    return WorkspaceEdit.of(manifest, edit)
                }
            }
        }
    }
}

/** Where a `<uses-permission>` goes in a manifest's text. */
object ManifestPermissionEdits {

    private val USES_PERMISSION = Regex("""<uses-permission(?:-sdk-23|-sdk-m)?\b[^>]*>""")

    /**
     * The edit adding a `<uses-permission>` for each of [permissions] not already declared in [text]: after the
     * last one there is, else before `<application`, else before `</manifest>`. Null when there is nothing to add
     * or no place to add it.
     */
    fun insertion(text: String, permissions: List<String>): DocumentEdit? {
        val declared = USES_PERMISSION.findAll(text).mapNotNull { m ->
            Regex("""android:name\s*=\s*"([^"]+)"""").find(m.value)?.groupValues?.get(1)
        }.toSet()
        val add = permissions.filter { it !in declared }
        if (add.isEmpty()) return null

        USES_PERMISSION.findAll(text).lastOrNull()?.let { last ->
            val indent = indentOfLine(text, last.range.first)
            return DocumentEdit(last.range.last + 1, 0, add.joinToString("") { "\n$indent${element(it)}" })
        }
        val anchor = Regex("""<application\b""").find(text)?.range?.first
            ?: Regex("""</manifest\s*>""").find(text)?.range?.first
            ?: return null
        val lineStart = text.lastIndexOf('\n', anchor - 1) + 1
        val indent = text.substring(lineStart, anchor).takeIf { it.isBlank() && it.isNotEmpty() } ?: "    "
        val trailer = if (text.startsWith("<application", anchor)) "\n" else ""
        return DocumentEdit(lineStart, 0, add.joinToString("") { "$indent${element(it)}\n" } + trailer)
    }

    private fun element(permission: String) = "<uses-permission android:name=\"$permission\" />"

    private fun indentOfLine(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
    }
}
