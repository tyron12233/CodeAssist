package dev.ide.android

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.resources.RIdAssignment
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.core.backend.DrawableMapping
import dev.ide.interp.PreviewResourceResolver
import dev.ide.preview.ResolvedValue
import dev.ide.preview.ValueFormat
import dev.ide.preview.impl.ProjectPreviewResources
import dev.ide.ui.editor.preview.UiDrawablePainter

/**
 * Interpreter-mediated project-resource resolution for the on-device Compose preview (the counterpart the
 * `dev.ide.interp.PreviewResourceResolver` port is injected with). On device the real `android.content.res
 * .Resources` behind `stringResource`/`colorResource`/… is the IDE app's own and holds none of the previewed
 * project's `res/`, so those calls are routed here instead.
 *
 * Backed by the module's merged [ResourceRepository] + the aapt-shaped [RIdAssignment] (the SAME ids the
 * synthetic `R` the editor resolves against uses, so `R.string.x` and `stringResource(id)` agree), with
 * [ProjectPreviewResources] as the value engine (recursive `@ref` chasing, colour/dimension parsing, `-night`
 * qualifier selection, drawable file lookup). Compose value types (`Color`/`Dp`/`Painter`) are pre-built here
 * because interp-compose can't name them. Constructed per render with the previewed [density]/[night] baked in.
 */
class AndroidPreviewResources(
    private val repo: ResourceRepository,
    private val namespace: String,
    density: Float,
    night: Boolean,
) : PreviewResourceResolver {

    private val ids = RIdAssignment(repo)
    private val effectiveDensity = if (density > 0f) density else 1f
    private val engine = ProjectPreviewResources(repo, density = effectiveDensity, night = night)

    /** Per-type sanitized-field-name → raw-resource-name map. The synthetic `R` sanitizes `.`/`:` → `_` for
     *  field names while [RIdAssignment] keys by the raw name (`R.string.Theme_App` ↔ raw `Theme.App`). */
    private val rawByField = HashMap<ResourceType, Map<String, String>>()

    override fun rClassField(ownerFqn: String, fieldName: String): Int? {
        val fqn = ownerFqn.replace('$', '.') // accept binary ($) or source (.) nesting
        val type = ResourceType.byRClass(fqn.substringAfterLast('.')) ?: return null
        if (fqn.substringBeforeLast('.') != "$namespace.R") return null // not this module's R
        val raw = rawNameOf(type, fieldName) ?: return null
        return ids.id(type, raw)
    }

    private fun rawNameOf(type: ResourceType, fieldName: String): String? {
        val map = rawByField.getOrPut(type) {
            repo.names(type).associateBy { it.replace('.', '_').replace(':', '_') }
        }
        return map[fieldName] ?: fieldName.takeIf { repo.has(type, it) }
    }

    override fun string(id: Int): String? {
        val (type, name) = ids.nameOf(id) ?: return null
        return (engine.resolve(ref(type, name), ValueFormat.STRING) as? ResolvedValue.Str)?.text?.toString()
    }

    override fun stringArray(id: Int): List<String>? {
        val (type, name) = ids.nameOf(id) ?: return null
        if (type != ResourceType.ARRAY) return null
        val src = repo.definitions(type, name).firstOrNull { it.source != null }?.source ?: return null
        return arrayItems(src, name)?.map { resolveStr(it) }
    }

    override fun plural(id: Int, quantity: Int): String? {
        val (type, name) = ids.nameOf(id) ?: return null
        if (type != ResourceType.PLURALS) return null
        val src = repo.definitions(type, name).firstOrNull { it.source != null }?.source ?: return null
        return pluralItem(src, name, quantity)?.let { resolveStr(it) }
    }

    override fun color(id: Int): Any? {
        val (type, name) = ids.nameOf(id) ?: return null
        val argb = (engine.resolve(ref(type, name), ValueFormat.COLOR) as? ResolvedValue.Color)?.argb ?: return null
        return Color(argb)
    }

    override fun dimension(id: Int): Any? {
        val (type, name) = ids.nameOf(id) ?: return null
        // The engine gives pixels at the previewed density; `dimensionResource` yields a density-independent Dp.
        val px = (engine.resolve(ref(type, name), ValueFormat.DIMENSION) as? ResolvedValue.Dimension)?.px ?: return null
        return (px / effectiveDensity).dp
    }

    override fun painter(id: Int): Any? {
        val (type, name) = ids.nameOf(id) ?: return null
        if (type != ResourceType.DRAWABLE && type != ResourceType.MIPMAP) return null
        // `backgroundDrawable` resolves the ref to either a bitmap-file marker or the parsed XML-drawable model
        // (vector/shape/layer-list, with @color/@dimen refs already chased against the project).
        return when (val d = engine.backgroundDrawable(ref(type, name))) {
            null -> null
            // A `<bitmap>`/file drawable: decode the image bytes to a BitmapPainter.
            is DrawablePreview.BitmapRef -> {
                val path = d.filePath ?: engine.imageFilePath(ref(type, name)) ?: return null
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.let { BitmapPainter(it.asImageBitmap()) }
            }
            // An XML drawable — render the parsed model (a `<vector>` draws crisp at any size, not rastered).
            else -> UiDrawablePainter(DrawableMapping.toUi(d), effectiveDensity)
        }
    }

    private fun ref(type: ResourceType, name: String) = "@${type.rClass}/$name"

    /** Resolve a raw item value (a literal, or a `@string/@…` reference) to its text; the raw string if it can't. */
    private fun resolveStr(raw: String): String =
        (engine.resolve(raw, ValueFormat.STRING) as? ResolvedValue.Str)?.text?.toString() ?: raw

    /** Parse `<string-array name=…>`'s `<item>` texts from [src]; null if the array isn't found. */
    private fun arrayItems(src: java.nio.file.Path, name: String): List<String>? {
        val root = parseXml(src) ?: return null
        val arrays = root.getElementsByTagName("string-array")
        for (i in 0 until arrays.length) {
            val el = arrays.item(i) as? org.w3c.dom.Element ?: continue
            if (el.getAttribute("name") == name) return childItems(el).map { it.textContent.trim() }
        }
        return null
    }

    /** The `<plurals name=…>` item text for [quantity] (English rule: 1 → `one`, else `other`; `other` is the
     *  fallback), or null if not found. */
    private fun pluralItem(src: java.nio.file.Path, name: String, quantity: Int): String? {
        val root = parseXml(src) ?: return null
        val plurals = root.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val el = plurals.item(i) as? org.w3c.dom.Element ?: continue
            if (el.getAttribute("name") != name) continue
            val wanted = if (quantity == 1) "one" else "other"
            var fallback: String? = null
            for (item in childItems(el)) {
                val text = item.textContent.trim()
                when (item.getAttribute("quantity")) {
                    wanted -> return text
                    "other" -> fallback = text
                }
            }
            return fallback
        }
        return null
    }

    private fun childItems(parent: org.w3c.dom.Element): List<org.w3c.dom.Element> {
        val out = ArrayList<org.w3c.dom.Element>()
        val kids = parent.childNodes
        for (i in 0 until kids.length) (kids.item(i) as? org.w3c.dom.Element)?.takeIf { it.tagName == "item" }?.let { out.add(it) }
        return out
    }

    private fun parseXml(src: java.nio.file.Path): org.w3c.dom.Document? = runCatching {
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder().parse(src.toFile())
    }.getOrNull()
}
