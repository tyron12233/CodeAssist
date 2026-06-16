package dev.ide.android.support.resources

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A dependency-free [ResourceModel] that parses `res/` directories the way aapt2/sdk-common do, with only
 * the parts the IDE needs: file resources by their type folder (config qualifiers stripped), value
 * resources from `values*` XML (`<string>`/`<color>`/`<style>`/`<declare-styleable>`/`<item type=…>`/…),
 * and `@+id/` declarations inside XML. Pure `java.nio` + JAXP, so it runs identically on desktop and ART.
 */
object StdlibResourceModel : ResourceModel {

    private val ID_BEARING = setOf(ResourceType.LAYOUT, ResourceType.MENU, ResourceType.NAVIGATION, ResourceType.DRAWABLE, ResourceType.XML, ResourceType.TRANSITION)
    private val ID_REF = Regex("""@\+id/([A-Za-z_][\w.]*)""")

    override fun parse(resDirs: List<Path>): ResourceRepository {
        val out = ArrayList<ResourceItem>()
        val styleableAttrs = LinkedHashMap<String, List<String>>()
        val styles = LinkedHashMap<String, StyleData>()
        val attrFormats = LinkedHashMap<String, String>()
        for (resDir in resDirs) {
            if (!Files.isDirectory(resDir)) continue
            runCatching {
                Files.newDirectoryStream(resDir).use { folders ->
                    for (folder in folders) {
                        if (!Files.isDirectory(folder)) continue
                        val raw = folder.fileName.toString()
                        val base = raw.substringBefore('-')          // strip `-hdpi`, `-night`, `-v21`, …
                        val qualifier = raw.substringAfter('-', "")
                        if (base == "values") {
                            forEachXml(folder) { parseValues(it, qualifier, out, styleableAttrs, styles, attrFormats) }
                        } else {
                            val type = ResourceType.fromFolder(base) ?: continue
                            Files.newDirectoryStream(folder).use { files ->
                                for (f in files) if (Files.isRegularFile(f)) {
                                    out += ResourceItem(type, baseName(f.fileName.toString()), source = f, qualifier = qualifier)
                                }
                            }
                            if (type in ID_BEARING) forEachXml(folder) { scanIds(it, qualifier, out) }
                        }
                    }
                }
            }
        }
        return ResourceRepository(out, styleableAttrs, styles, attrFormats)
    }

    private fun forEachXml(dir: Path, block: (Path) -> Unit) {
        runCatching { Files.newDirectoryStream(dir, "*.xml").use { it.forEach(block) } }
    }

    private fun parseValues(
        file: Path,
        qualifier: String,
        out: MutableList<ResourceItem>,
        styleableAttrs: MutableMap<String, List<String>>,
        styles: MutableMap<String, StyleData>,
        attrFormats: MutableMap<String, String>,
    ) {
        val doc = runCatching { documentBuilder().parse(file.toFile()) }.getOrNull() ?: return
        val root = doc.documentElement ?: return
        val children = root.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            if (el.nodeType != Node.ELEMENT_NODE) continue
            if (el.tagName == "style") captureStyle(el, styles)
            if (el.tagName == "attr") el.getAttribute("format").ifEmpty { null }?.let { attrFormats[sanitize(el.getAttribute("name"))] = it }
            val name = sanitize(el.getAttribute("name")).ifEmpty { continue }
            val type = when (el.tagName) {
                "item" -> ResourceType.byRClass(el.getAttribute("type").ifEmpty { "id" })
                else -> ResourceType.fromValueTag(el.tagName)
            } ?: continue
            out += ResourceItem(type, name, source = file, qualifier = qualifier, value = el.textContent?.trim()?.ifEmpty { null })
            if (type == ResourceType.STYLEABLE) {
                // a <declare-styleable>'s child <attr>s are also R.attr entries; remember the ordered list
                // so R.styleable.<name> can emit the matching int[] + per-attr index constants.
                val attrs = el.childNodes
                val attrNames = ArrayList<String>()
                for (j in 0 until attrs.length) {
                    val a = attrs.item(j) as? Element ?: continue
                    if (a.tagName == "attr") sanitize(a.getAttribute("name")).ifEmpty { null }?.let {
                        out += ResourceItem(ResourceType.ATTR, it, source = file, qualifier = qualifier)
                        attrNames += it
                        a.getAttribute("format").ifEmpty { null }?.let { fmt -> attrFormats[it] = fmt }
                    }
                }
                if (attrNames.isNotEmpty()) styleableAttrs[name] = attrNames
            }
        }
    }

    /** Capture a `<style>`/theme by its RAW (dotted) name so parent-chain walking and `?attr` resolution work. */
    private fun captureStyle(el: Element, styles: MutableMap<String, StyleData>) {
        val rawName = el.getAttribute("name").trim().ifEmpty { return }
        val parent = el.getAttribute("parent").trim().ifEmpty { null }
        val items = LinkedHashMap<String, String>()
        val kids = el.childNodes
        for (j in 0 until kids.length) {
            val item = kids.item(j) as? Element ?: continue
            if (item.tagName != "item") continue
            val itemName = item.getAttribute("name").trim().ifEmpty { continue }
            items[itemName] = item.textContent?.trim().orEmpty()
        }
        styles[rawName] = StyleData(parent, items)
    }

    private fun scanIds(file: Path, qualifier: String, out: MutableList<ResourceItem>) {
        val text = runCatching { String(Files.readAllBytes(file), Charsets.UTF_8) }.getOrNull() ?: return
        ID_REF.findAll(text).forEach { out += ResourceItem(ResourceType.ID, sanitize(it.groupValues[1]), source = file, qualifier = qualifier) }
    }

    /** A file resource's name: the part before the extension (Android resource file names contain no '.'). */
    private fun baseName(fileName: String): String = sanitize(fileName.substringBefore('.'))

    private fun sanitize(name: String): String = name.replace('.', '_').replace('-', '_').trim()

    private fun documentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        isExpandEntityReferences = false
    }.newDocumentBuilder()
}
