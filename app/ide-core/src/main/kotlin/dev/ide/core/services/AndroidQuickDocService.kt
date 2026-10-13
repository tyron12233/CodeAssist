package dev.ide.core.services

import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.android.support.navigation.XmlNavTarget
import dev.ide.android.support.navigation.sanitizeResourceName
import dev.ide.android.support.resources.ResourceType
import dev.ide.lang.resolve.DocFormat
import dev.ide.lang.resolve.QuickDocInfo
import dev.ide.lang.resolve.SymbolKind
import dev.ide.model.Module
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Quick Doc for Android XML and `R.type.name`: an attribute's formats, closed values and the widgets that
 * declare it; a resource's value in every configuration (`values`, `values-es`, `values-night`, ...) or the
 * files that provide it.
 *
 * The framework attribute data is the bundled SDK metadata, which carries no prose, so this documents the
 * shape rather than the meaning. A custom attribute is read from the project's (and its AARs') `attrs.xml`.
 */
internal class AndroidQuickDocService(
    private val files: AndroidProjectFiles,
    private val navigation: AndroidNavigationService,
    private val sdkMetadata: () -> AndroidSdkMetadata,
    private val customAttrs: (Module) -> AndroidSdkMetadata?,
) {

    fun quickDocAt(file: Path, text: String, offset: Int): QuickDocInfo? {
        val module = files.androidModuleOf(file) ?: return null
        return when (val target = navigation.targetAt(file, text, offset)) {
            is XmlNavTarget.Attribute -> attributeDoc(module, target.prefix, target.localName)
            // `?attr/x` / a style `<item name="x">` documents the attribute, not a value.
            is XmlNavTarget.Resource ->
                if (target.type == ResourceType.ATTR) attributeDoc(module, if (target.isFramework) "android" else "app", target.name)
                else resourceDoc(target)
            else -> null
        }
    }

    private fun attributeDoc(module: Module, prefix: String?, localName: String): QuickDocInfo? {
        val info = if (prefix == "android") sdkMetadata().attributeInfo(localName)
        else customAttrs(module)?.attributeInfo(localName) ?: sdkMetadata().attributeInfo(localName)?.takeIf { prefix == null }
        info ?: return null
        val lines = ArrayList<String>()
        if (info.formats.isNotEmpty()) lines += "Format: " + info.formats.joinToString(" | ") { "`${it.name.lowercase()}`" }
        if (info.enumValues.isNotEmpty()) lines += "Values: " + info.enumValues.joinToString(", ") { "`$it`" }
        if (info.flagValues.isNotEmpty()) lines += "Flags (combine with `|`): " + info.flagValues.joinToString(", ") { "`$it`" }
        if (info.declaredBy.isNotEmpty()) {
            val shown = info.declaredBy.take(MAX_DECLARERS).joinToString(", ") { it.replace("_Layout", " (layout params)") }
            val more = info.declaredBy.size - MAX_DECLARERS
            lines += "Declared by: $shown" + if (more > 0) " and $more more" else ""
        }
        val name = "${prefix ?: "app"}:$localName"
        return QuickDocInfo(
            signature = name,
            name = localName,
            kind = SymbolKind.FIELD,
            container = if (prefix == "android") "Framework attribute" else "Custom attribute",
            doc = lines.joinToString("\n").ifEmpty { null },
            docFormat = DocFormat.KDOC,
        )
    }

    private fun resourceDoc(target: XmlNavTarget.Resource): QuickDocInfo? {
        val ref = "@" + (if (target.isFramework) "android:" else "") + "${target.type.rClass}/${target.name}"
        if (target.packageName != null && !target.isFramework) return null
        val decls = if (target.isFramework) emptyList()
        else files.declarations(target.type, sanitizeResourceName(target.name))
            .sortedBy { AndroidNavigationService.qualifierRank(it.filePath) }
        val lines = ArrayList<String>()
        for (d in decls.take(MAX_VARIANTS)) {
            val label = AndroidNavigationService.resourceFileLabel(Paths.get(d.filePath))
            lines += if (d.value != null) "• `$label`: ${d.value}" else "• `$label`"
        }
        if (decls.size > MAX_VARIANTS) lines += "• and ${decls.size - MAX_VARIANTS} more"
        val container = when {
            target.isFramework -> "Framework ${target.type.rClass} resource"
            decls.isEmpty() -> "Unresolved ${target.type.rClass} resource"
            decls.size == 1 -> "${target.type.rClass} resource"
            else -> "${target.type.rClass} resource, ${decls.size} declarations"
        }
        return QuickDocInfo(
            signature = ref,
            name = target.name,
            kind = SymbolKind.FIELD,
            container = container,
            doc = lines.joinToString("\n").ifEmpty { null },
            docFormat = DocFormat.KDOC,
        )
    }

    private companion object {
        const val MAX_VARIANTS = 12
        const val MAX_DECLARERS = 8
    }
}
