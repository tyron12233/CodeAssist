package dev.ide.android.support.manifest

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A from-scratch implementation of Android's manifest merger ([com.android.manifmerger.ManifestMerger2]),
 * faithful to its algorithm: the app manifest is the highest-priority document, and each library manifest
 * (in decreasing priority) is merged into it. The result feeds `aapt2 link` in place of the raw source
 * manifest, so a dependency that contributes `<service>`/`<receiver>`/`<provider>`/`<meta-data>`/permission
 * entries — Firebase, Google Play Services, AndroidX, WorkManager — actually lands in the packaged manifest.
 *
 * It is JAXP/DOM-based (works on the desktop JVM and on ART) and Android-agnostic in its plumbing: the
 * caller supplies the placeholder values. Covered, matching ManifestMerger2:
 *  - **Node identity** by element type + key (`android:name` for most; `android:authorities` falls back for
 *    providers; `intent-filter`/`data` keyed by content; `uses-feature` by name-or-glEsVersion; singletons
 *    like `application`/`uses-sdk` keyed by type alone) — see [nodeKey].
 *  - **Attribute merge** with namespace awareness: a lower node's attribute is added when absent; an equal
 *    value is a no-op; a conflicting value keeps the higher-priority one (ManifestMerger2 errors here and
 *    asks for `tools:replace`; an IDE merge stays lenient and records a warning instead, except under
 *    `tools:strict`). The root `<manifest package>` and the `<uses-sdk>` min/target/maxSdkVersion are
 *    exempt: every library differs there by design, so the app values win silently (see [isPrimaryAuthoritative]).
 *  - **All `tools:` markers**: `tools:node` = `merge` (default) / `replace` / `remove` / `removeAll` /
 *    `strict` / `mergeOnlyAttributes`; `tools:replace="a,b"`; `tools:remove="a,b"`; `tools:strict`;
 *    `tools:selector` (narrows remove/removeAll to one library package). The `tools:` namespace and all
 *    `tools:*` attributes are stripped from the output (aapt2 does not understand them).
 *  - **Placeholders**: `${name}` in any attribute value is replaced from the supplied map (`${applicationId}`
 *    being the one Firebase/Play Services rely on, e.g. a `FirebaseInitProvider` authority). An unresolved
 *    placeholder is left verbatim and reported (ManifestMerger2 fails the build; the IDE keeps going).
 *  - **`uses-sdk`**: the app owns its SDK levels (the build config feeds `aapt2 --min/--target-sdk-version`),
 *    so a library's `<uses-sdk>` never reaches the output: its min/target/maxSdkVersion are dropped if the app
 *    declares its own, and the whole element is skipped if the app declares none (importing it would make the
 *    library's `targetSdkVersion` the app's effective target). A library declaring a higher `minSdkVersion`
 *    than the app's is still reported (overridable via `tools:overrideLibrary`).
 *  - **Class-name resolution**: a library's relative component name (`.Foo` or a bare `Foo` on an
 *    `<activity>`/`<activity-alias>`/`<service>`/`<receiver>`/`<provider>`/`<application>`/`<instrumentation>`)
 *    is expanded to the library's fully-qualified package before merge (`PackageParser.buildClassName`), so it
 *    still resolves once it sits under the app's package. Identifier `android:name`s and authorities are left alone.
 *
 * Deliberately out of scope (rare; not needed by the libraries this targets): implicit system-permission
 * injection for legacy SDK combinations, and merge-blame/provenance records.
 */
object ManifestMerger {

    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    const val TOOLS_NS = "http://schemas.android.com/tools"

    enum class Severity { INFO, WARNING, ERROR }
    data class Message(val severity: Severity, val text: String)
    data class Result(val xml: String, val messages: List<Message>) {
        val hasErrors: Boolean get() = messages.any { it.severity == Severity.ERROR }
    }

    /** Merge files: the app [primary] + [libraries] (already in decreasing priority). */
    fun merge(primary: Path, libraries: List<Path>, placeholders: Map<String, String> = emptyMap()): Result {
        val primaryXml = Files.readAllBytes(primary).toString(Charsets.UTF_8)
        val libXmls = libraries.mapNotNull { lib ->
            runCatching { lib to Files.readAllBytes(lib).toString(Charsets.UTF_8) }.getOrNull()
        }
        return mergeXml(primaryXml, libXmls.map { it.second }, placeholders, libXmls.map { it.first.toString() })
    }

    /**
     * Merge raw manifest XML — the testable core. [libraries] are in decreasing priority; [libraryNames]
     * (optional, parallel to [libraries]) name each library in diagnostics and back `tools:selector`.
     */
    fun mergeXml(
        primary: String,
        libraries: List<String>,
        placeholders: Map<String, String> = emptyMap(),
        libraryNames: List<String> = emptyList(),
    ): Result {
        val messages = ArrayList<Message>()
        val mergedDoc = runCatching { parse(primary) }.getOrElse {
            return Result(primary, listOf(Message(Severity.ERROR, "primary manifest is not valid XML: ${it.message}")))
        }
        substitutePlaceholders(mergedDoc.documentElement, placeholders, "primary manifest", messages)

        libraries.forEachIndexed { i, libXml ->
            val name = libraryNames.getOrNull(i) ?: "library #${i + 1}"
            val libDoc = runCatching { parse(libXml) }.getOrNull()
            if (libDoc == null) {
                messages += Message(Severity.WARNING, "skipping $name: not valid XML")
                return@forEachIndexed
            }
            substitutePlaceholders(libDoc.documentElement, placeholders, name, messages)
            val libPkg = libDoc.documentElement.getAttribute("package").ifEmpty { null }
            // Resolve the library's relative component class names against ITS package before merging: a
            // ".Foo" (or bare "Foo") would otherwise resolve against the app package once merged under it.
            if (libPkg != null) expandClassNames(libDoc.documentElement, libPkg)
            mergeElement(mergedDoc.documentElement, libDoc.documentElement, mergedDoc, name, libPkg, messages)
        }

        // Post-process: honour remove/removeAll markers, then strip tools:remove'd attributes and the whole
        // tools namespace so the output is a clean, aapt2-consumable manifest.
        applyRemovals(mergedDoc.documentElement)
        stripToolsArtifacts(mergedDoc.documentElement)
        return Result(serialize(mergedDoc), messages)
    }

    // ---- merge core ----------------------------------------------------------------------------

    /** Merge [from] (lower priority) into [into] (higher priority); both are the same element type. */
    private fun mergeElement(into: Element, from: Element, doc: Document, lib: String, libPkg: String?, msgs: MutableList<Message>) {
        when (toolsNode(into)) {
            "replace" -> return            // higher node fully replaces the lower: ignore from entirely
            "remove", "removeAll" -> return // marker node: it deletes lower nodes (handled by appendChildMerging/applyRemovals)
        }
        mergeAttributes(into, from, doc, lib, msgs)
        if (toolsNode(into) == "mergeOnlyAttributes") return
        mergeChildren(into, from, doc, lib, libPkg, msgs)
        if (into.tagName == "uses-sdk") checkUsesSdk(into, from, lib, msgs)
    }

    private fun mergeAttributes(into: Element, from: Element, doc: Document, lib: String, msgs: MutableList<Message>) {
        val replace = toolsList(into, "replace")
        val strict = toolsNode(into) == "strict"
        val attrs = from.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i) as Attr
            if (a.namespaceURI == TOOLS_NS) continue                 // never copy tools:* across
            if (isXmlnsDecl(a)) continue
            val ns = a.namespaceURI
            val local = a.localName ?: a.name
            // ManifestMerger2 never merges a library's package or its uses-sdk version attributes up into the
            // app: the app values are authoritative, so a difference is expected, not a conflict to report.
            // (A library demanding a *higher* minSdk is still flagged separately by checkUsesSdk.)
            if (isPrimaryAuthoritative(into, ns, local)) continue
            val higher = getAttr(into, ns, local)
            when {
                higher == null -> setAttr(into, doc, a)              // absent above → take the library's
                higher == a.value -> {}                              // identical → fine
                local in replace -> {}                               // higher wins by explicit tools:replace
                strict -> msgs += Message(Severity.ERROR,
                    "manifest conflict on @${a.name} (\"$higher\" vs \"${a.value}\" from $lib); tools:strict")
                else -> msgs += Message(Severity.WARNING,
                    "manifest attribute @${a.name} differs (kept \"$higher\", $lib has \"${a.value}\"); add tools:replace=\"${a.name}\" to silence")
            }
        }
    }

    private fun mergeChildren(into: Element, from: Element, doc: Document, lib: String, libPkg: String?, msgs: MutableList<Message>) {
        val existing = childElements(into).associateBy { nodeKey(it) }
        // removeAll markers on the higher node block any lower child of that type (optionally one selector pkg).
        val removeAllTypes = childElements(into)
            .filter { toolsNode(it) == "removeAll" }
            .associate { it.tagName to toolsAttr(it, "selector") }
        for (child in childElements(from)) {
            val type = child.tagName
            // A removeAll marker on the higher node blocks every lower child of that type (optionally only
            // the library whose package matches tools:selector).
            if (removeAllTypes.containsKey(type)) {
                val selector = removeAllTypes[type]
                if (selector == null || selector == libPkg) continue
            }
            val key = nodeKey(child)
            val match = existing[key]
            if (match == null) {
                // A library's <uses-sdk> only carries min/target/maxSdkVersion, which the app's build config
                // owns (aapt2 injects them from its flags). Never import a library's into an app that declares
                // none, or the library's targetSdkVersion silently becomes the app's effective target (which
                // breaks insets / forces edge-to-edge). The match case is already neutralised by
                // isPrimaryAuthoritative; this closes the wholesale-append path.
                if (type == "uses-sdk") continue
                into.appendChild(doc.importNode(child, true))
            } else when (toolsNode(match)) {
                "remove" -> {}                                       // marker: drop the lower node, keep marker for cleanup
                "removeAll" -> {}
                "replace" -> {}                                      // keep the higher node as-is
                else -> mergeElement(match, child, doc, lib, libPkg, msgs)
            }
        }
    }

    /** uses-sdk: the app's min/target win; a library asking for a higher minSdk is flagged (unless overridden). */
    private fun checkUsesSdk(into: Element, from: Element, lib: String, msgs: MutableList<Message>) {
        val appMin = getAttr(into, ANDROID_NS, "minSdkVersion")?.toIntOrNull() ?: 1
        val libMin = getAttr(from, ANDROID_NS, "minSdkVersion")?.toIntOrNull() ?: return
        val override = toolsList(into, "overrideLibrary").isNotEmpty() || toolsAttr(into, "overrideLibrary") != null
        if (libMin > appMin && !override) msgs += Message(Severity.WARNING,
            "$lib requires minSdkVersion $libMin but the app declares $appMin; raise it or add tools:overrideLibrary")
        // The app's uses-sdk values are authoritative — do not let the library lower/raise them.
    }

    /**
     * Attributes the app manifest owns outright, so a library's differing value is absorbed silently rather
     * than reported as a conflict (matching ManifestMerger2): the root `<manifest package>` (every library
     * declares its own package, which namespaces the library and never overrides the app's) and the
     * `<uses-sdk>` min/target/maxSdkVersion (the app's build config is authoritative).
     */
    private fun isPrimaryAuthoritative(into: Element, ns: String?, local: String): Boolean = when (into.tagName) {
        "manifest" -> ns == null && local == "package"
        "uses-sdk" -> ns == ANDROID_NS && (local == "minSdkVersion" || local == "targetSdkVersion" || local == "maxSdkVersion")
        else -> false
    }

    // ---- removals + tools cleanup --------------------------------------------------------------

    /** Delete every element flagged `tools:node="remove"`/`"removeAll"` (they exist only to delete others). */
    private fun applyRemovals(root: Element) {
        for (child in childElements(root)) {
            val mode = toolsNode(child)
            if (mode == "remove" || mode == "removeAll") { root.removeChild(child); continue }
            applyRemovals(child)
        }
    }

    /** Strip the tools namespace declaration, every `tools:*` attribute, and apply each `tools:remove` list. */
    private fun stripToolsArtifacts(root: Element) {
        for (local in toolsList(root, "remove")) {
            // tools:remove names attributes to drop from this node (e.g. tools:remove="android:label").
            removeAttrByLocal(root, local)
        }
        // Collect first (live NamedNodeMap mutates under iteration).
        val toolsAttrs = ArrayList<Attr>()
        val xmlnsTools = ArrayList<Attr>()
        val attrs = root.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i) as Attr
            if (a.namespaceURI == TOOLS_NS) toolsAttrs += a
            else if (a.value == TOOLS_NS && (a.name == "xmlns:tools" || a.localName == "tools")) xmlnsTools += a
        }
        toolsAttrs.forEach { root.removeAttributeNode(it) }
        xmlnsTools.forEach { runCatching { root.removeAttributeNode(it) } }
        childElements(root).forEach { stripToolsArtifacts(it) }
    }

    // ---- node identity -------------------------------------------------------------------------

    /** ManifestMerger2's node key: element type + the identifying attribute(s) for that type. */
    private fun nodeKey(e: Element): String {
        val type = e.tagName
        return when (type) {
            // Singletons: at most one per parent, keyed by type alone.
            "manifest", "application", "uses-sdk", "supports-screens", "compatible-screens",
            "supports-gl-texture" -> type
            "intent-filter" -> "$type|${intentFilterKey(e)}"
            "data" -> "$type|${dataKey(e)}"
            "uses-feature" -> "$type|${androidAttr(e, "name") ?: "glEs:${androidAttr(e, "glEsVersion")}"}"
            "provider" -> "$type|${androidAttr(e, "name") ?: "auth:${androidAttr(e, "authorities")}"}"
            "screen" -> "$type|${androidAttr(e, "screenSize")}"
            else -> "$type|${androidAttr(e, "name") ?: signatureOf(e)}"
        }
    }

    /** An intent-filter has no name: key it by its sorted actions/categories + data signatures. */
    private fun intentFilterKey(e: Element): String {
        val actions = childElements(e).filter { it.tagName == "action" }.mapNotNull { androidAttr(it, "name") }.sorted()
        val cats = childElements(e).filter { it.tagName == "category" }.mapNotNull { androidAttr(it, "name") }.sorted()
        val data = childElements(e).filter { it.tagName == "data" }.map { dataKey(it) }.sorted()
        return "a=${actions.joinToString(",")};c=${cats.joinToString(",")};d=${data.joinToString(",")}"
    }

    /** A <data> element is keyed by all of its android:* attributes (scheme/host/port/path/mimeType/…). */
    private fun dataKey(e: Element): String {
        val attrs = e.attributes
        val parts = ArrayList<String>()
        for (i in 0 until attrs.length) {
            val a = attrs.item(i) as Attr
            if (a.namespaceURI == ANDROID_NS) parts += "${a.localName}=${a.value}"
        }
        return parts.sorted().joinToString(";")
    }

    /** Fallback key for a keyless element: a stable signature of all its attributes. */
    private fun signatureOf(e: Element): String {
        val attrs = e.attributes
        val parts = ArrayList<String>()
        for (i in 0 until attrs.length) {
            val a = attrs.item(i) as Attr
            if (a.namespaceURI != TOOLS_NS && !isXmlnsDecl(a)) parts += "${a.name}=${a.value}"
        }
        return parts.sorted().joinToString(";")
    }

    // ---- placeholders --------------------------------------------------------------------------

    // The closing `}` is escaped: Android's ICU regex rejects a bare `}` (the desktop JDK treats it as a
    // literal), so an unescaped one threw PatternSyntaxException in this object's initializer on ART.
    private val PLACEHOLDER = Regex("""\$\{([^}]+)\}""")

    private fun substitutePlaceholders(root: Element, values: Map<String, String>, where: String, msgs: MutableList<Message>) {
        val attrs = root.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i) as Attr
            if ("\${" !in a.value) continue
            a.value = PLACEHOLDER.replace(a.value) { m ->
                val key = m.groupValues[1]
                values[key] ?: run {
                    msgs += Message(Severity.WARNING, "unresolved manifest placeholder \${$key} in @${a.name} ($where)")
                    m.value
                }
            }
        }
        childElements(root).forEach { substitutePlaceholders(it, values, where, msgs) }
    }

    // ---- class-name resolution -----------------------------------------------------------------

    /**
     * Component element attributes whose value is a class name. Only these resolve relative names against the
     * package; identifier-valued `android:name`s (`<uses-permission>`, `<meta-data>`, `<action>`, …) and
     * `<provider android:authorities>` are deliberately absent so they are never rewritten.
     */
    private val CLASS_NAME_ATTRS: Map<String, Set<String>> = mapOf(
        "application" to setOf("name", "backupAgent"),
        "activity" to setOf("name", "parentActivityName"),
        "activity-alias" to setOf("name", "targetActivity"),
        "service" to setOf("name"),
        "receiver" to setOf("name"),
        "provider" to setOf("name"),
        "instrumentation" to setOf("name"),
    )

    /**
     * Rewrite a library subtree's relative component class names to absolute against the library [pkg], so a
     * `<service android:name=".Foo">` becomes `pkg.Foo` and still resolves once it is merged under the app's
     * package. Mirrors `PackageParser.buildClassName`: a leading `.` or a bare (dot-less) name is relative.
     */
    private fun expandClassNames(el: Element, pkg: String) {
        CLASS_NAME_ATTRS[el.tagName]?.forEach { local ->
            val attr = el.getAttributeNodeNS(ANDROID_NS, local) ?: return@forEach
            val resolved = resolveClassName(attr.value, pkg)
            if (resolved != attr.value) attr.value = resolved
        }
        childElements(el).forEach { expandClassNames(it, pkg) }
    }

    private fun resolveClassName(name: String, pkg: String): String = when {
        name.isEmpty() -> name
        name[0] == '.' -> pkg + name           // ".Foo" -> "pkg.Foo"
        '.' !in name -> "$pkg.$name"           // bare "Foo" -> "pkg.Foo"
        else -> name                           // already fully qualified
    }

    // ---- tools/attribute helpers ---------------------------------------------------------------

    private fun toolsNode(e: Element): String? = toolsAttr(e, "node")?.lowercase()
    private fun toolsAttr(e: Element, local: String): String? =
        if (e.hasAttributeNS(TOOLS_NS, local)) e.getAttributeNS(TOOLS_NS, local).ifEmpty { null } else null

    /** A comma/space-separated tools list (e.g. tools:replace="android:label, android:icon"), normalized to
     *  local attribute names (prefix stripped). */
    private fun toolsList(e: Element, local: String): Set<String> =
        toolsAttr(e, local)?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { it.substringAfter(':') }?.toSet() ?: emptySet()

    private fun androidAttr(e: Element, local: String): String? =
        if (e.hasAttributeNS(ANDROID_NS, local)) e.getAttributeNS(ANDROID_NS, local).ifEmpty { null } else null

    private fun getAttr(e: Element, ns: String?, local: String): String? =
        if (ns != null) (if (e.hasAttributeNS(ns, local)) e.getAttributeNS(ns, local) else null)
        else (if (e.hasAttribute(local)) e.getAttribute(local) else null)

    private fun setAttr(into: Element, doc: Document, source: Attr) {
        val imported = doc.importNode(source, true) as Attr
        into.setAttributeNodeNS(imported)
    }

    private fun removeAttrByLocal(e: Element, local: String) {
        if (e.hasAttributeNS(ANDROID_NS, local)) e.removeAttributeNS(ANDROID_NS, local)
        if (e.hasAttribute(local)) e.removeAttribute(local)
    }

    private fun isXmlnsDecl(a: Attr): Boolean = a.name == "xmlns" || a.name.startsWith("xmlns:")

    private fun childElements(e: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = e.childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { out += it }
        return out
    }

    // ---- IO ------------------------------------------------------------------------------------

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    /**
     * Hand-rolled DOM → XML writer. Deliberately avoids `javax.xml.transform` (TransformerFactory): Android
     * does not ship a reliable Transformer, so an identity transform is an ART hazard. Manifest elements are
     * attribute-only (no mixed text content), so this writes elements + attributes with stable indentation.
     */
    private fun serialize(doc: Document): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        doc.documentElement?.let { writeElement(it, sb, 0) }
        return sb.toString()
    }

    private fun writeElement(el: Element, sb: StringBuilder, depth: Int) {
        val indent = "    ".repeat(depth)
        sb.append(indent).append('<').append(el.tagName)
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i) as Attr
            sb.append(' ').append(a.name).append("=\"").append(escapeXml(a.value, attribute = true)).append('"')
        }
        val children = childElements(el)
        if (children.isEmpty()) {
            sb.append("/>\n")
        } else {
            sb.append(">\n")
            children.forEach { writeElement(it, sb, depth + 1) }
            sb.append(indent).append("</").append(el.tagName).append(">\n")
        }
    }

    private fun escapeXml(s: String, attribute: Boolean): String {
        val sb = StringBuilder(s.length)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> if (attribute) sb.append("&quot;") else sb.append('"')
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
