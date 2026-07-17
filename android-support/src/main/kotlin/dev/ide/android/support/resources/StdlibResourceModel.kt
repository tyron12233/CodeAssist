package dev.ide.android.support.resources

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.SAXParserFactory

/** Resource names can't contain `.`/`-` (those map to `_` in `R`); also trims surrounding whitespace. */
private fun sanitize(name: String): String = name.replace('.', '_').replace('-', '_').trim()

/**
 * A dependency-free [ResourceModel] that parses `res/` directories the way aapt2/sdk-common do, with only
 * the parts the IDE needs: file resources by their type folder (config qualifiers stripped), value
 * resources from `values*` XML (`<string>`/`<color>`/`<style>`/`<declare-styleable>`/`<item type=…>`/…),
 * and `@+id/` declarations inside XML. Pure `java.nio` + JAXP, so it runs identically on desktop and ART.
 *
 * Value files are read with a **streaming SAX parser**, not a DOM. A DOM materializes every element, attribute
 * and text node of the whole file as live objects; across a Material/AndroidX dependency set (hundreds of
 * config-qualified value-resource XML files, each re-parsed by every consumer) that allocation dominated the
 * heap and OOM'd a tight device. SAX keeps only the handful of fields per resource we actually retain.
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
        val handler = ValuesHandler(file, qualifier, out, styleableAttrs, styles, attrFormats)
        val before = out.size
        val ok = runCatching { Files.newInputStream(file).use { newSaxParser().parse(it, handler) } }.isSuccess
        if (!ok) {
            // Malformed XML: SAX abandons the file at the first error. Drop its partial emission for this file
            // and recover ALL declarations leniently (see [LenientValuesScan]) so a single broken values file
            // (a stray tag while editing) doesn't wipe R.string/R.color/… completion. The structured extras
            // (styleable attr arrays, style items) aren't recovered — the resource NAMES the R class needs are.
            while (out.size > before) out.removeAt(out.size - 1)
            val text = runCatching { String(Files.readAllBytes(file), Charsets.UTF_8) }.getOrNull()
            if (text != null) LenientValuesScan.scan(file.toString(), text).forEach {
                out += ResourceItem(it.type, it.name, source = file, qualifier = qualifier)
            }
        }
    }

    /**
     * One value element being parsed: the resource attributes off its start tag, its accumulated text (the SAX
     * equivalent of DOM `textContent` - own + descendant characters), and, while open, the child data a
     * `<style>`/`<declare-styleable>` collects from its `<item>`/`<attr>` children.
     */
    private class Frame(val tag: String, val name: String, val type: String, val format: String, val parent: String) {
        val text = StringBuilder()
        val styleItems = LinkedHashMap<String, String>()   // <style>:  item name -> value
        val attrNames = ArrayList<String>()                // <declare-styleable>: ordered child <attr> names
    }

    private class ValuesHandler(
        private val file: Path,
        private val qualifier: String,
        private val out: MutableList<ResourceItem>,
        private val styleableAttrs: MutableMap<String, List<String>>,
        private val styles: MutableMap<String, StyleData>,
        private val attrFormats: MutableMap<String, String>,
    ) : DefaultHandler() {
        private val stack = ArrayList<Frame>()

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            stack.add(Frame(
                tag = qName,
                name = attrs.getValue("name") ?: "",
                type = attrs.getValue("type") ?: "",
                format = attrs.getValue("format") ?: "",
                parent = attrs.getValue("parent") ?: "",
            ))
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (stack.isNotEmpty()) stack[stack.size - 1].text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val frame = stack.removeAt(stack.size - 1)
            val parent = stack.lastOrNull()
            val depth = stack.size   // depth AFTER pop: 1 = top-level resource element (child of <resources>)
            when {
                // A child of a top-level resource element: a declare-styleable's <attr>, or a <style>'s <item>.
                depth == 2 && parent != null -> {
                    if (frame.tag == "attr" && parent.tag == "declare-styleable") {
                        sanitize(frame.name).ifEmpty { null }?.let { an ->
                            out += ResourceItem(ResourceType.ATTR, an, source = file, qualifier = qualifier)
                            parent.attrNames += an
                            frame.format.ifEmpty { null }?.let { attrFormats[an] = it }
                        }
                    } else if (frame.tag == "item" && parent.tag == "style") {
                        frame.name.trim().ifEmpty { null }?.let { parent.styleItems[it] = frame.text.toString().trim() }
                    }
                }
                // A top-level resource element (direct child of <resources>).
                depth == 1 -> emitResource(frame)
            }
            parent?.text?.append(frame.text)   // bubble text up so an ancestor's `text` == its DOM textContent
        }

        private fun emitResource(frame: Frame) {
            if (frame.tag == "style") {
                frame.name.trim().ifEmpty { null }?.let { styles[it] = StyleData(frame.parent.trim().ifEmpty { null }, frame.styleItems) }
            }
            if (frame.tag == "attr") frame.format.ifEmpty { null }?.let { attrFormats[sanitize(frame.name)] = it }
            val name = sanitize(frame.name).ifEmpty { return }
            val type = when (frame.tag) {
                "item" -> ResourceType.byRClass(frame.type.ifEmpty { "id" })
                else -> ResourceType.fromValueTag(frame.tag)
            } ?: return
            out += ResourceItem(type, name, source = file, qualifier = qualifier, value = frame.text.toString().trim().ifEmpty { null })
            if (type == ResourceType.STYLEABLE && frame.attrNames.isNotEmpty()) styleableAttrs[name] = frame.attrNames
        }
    }

    private fun scanIds(file: Path, qualifier: String, out: MutableList<ResourceItem>) {
        val text = runCatching { String(Files.readAllBytes(file), Charsets.UTF_8) }.getOrNull() ?: return
        ID_REF.findAll(text).forEach { out += ResourceItem(ResourceType.ID, sanitize(it.groupValues[1]), source = file, qualifier = qualifier) }
    }

    /** A file resource's name: the part before the extension (Android resource file names contain no '.'). */
    private fun baseName(fileName: String): String = sanitize(fileName.substringBefore('.'))

    /** Shared SAX factory (creation does service discovery - do it once); a fresh parser per file is cheap. */
    private val saxFactory: SAXParserFactory by lazy {
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
    }

    private fun newSaxParser() = synchronized(saxFactory) { saxFactory.newSAXParser() }
}
