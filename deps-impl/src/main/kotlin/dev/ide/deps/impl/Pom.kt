package dev.ide.deps.impl

import dev.ide.model.Coordinate
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/** A `group:name` identity, the granularity at which Maven dedups and excludes. */
data class GA(val group: String, val name: String) {
    override fun toString() = "$group:$name"
}

/** A `<dependency>` entry as written in a POM (version may be null → filled from dependencyManagement). */
data class PomDependency(
    val groupId: String,
    val artifactId: String,
    val version: String?,
    val scope: String,            // compile | runtime | provided | test | system
    val optional: Boolean,
    val type: String,             // jar | aar | pom | ...
    val classifier: String?,
    val exclusions: Set<GA>,
) {
    val ga: GA get() = GA(groupId, artifactId)
}

/** A `<dependencyManagement>` entry — supplies a version (and possibly scope) for matching `ga`s. */
data class ManagedDep(val ga: GA, val version: String?, val scope: String?)

/**
 * A `<distributionManagement><relocation>` — the artifact has moved to a new coordinate (a rename/split).
 * Any field the relocation omits keeps the current POM's value (Maven semantics), so `com.itextpdf:itext7-core`
 * relocating with only `<artifactId>itext-core</artifactId>` means same group + version, new name. Consumers
 * should resolve the target instead of the (usually artifact-less) stub.
 */
data class PomRelocation(val groupId: String?, val artifactId: String?, val version: String?)

/**
 * One POM parsed verbatim (no parent merge, no property substitution yet). The resolver walks the
 * [parent] chain to build the *effective* POM (merged properties + dependencyManagement) before reading
 * [dependencies] — exactly the data needed to resolve transitives.
 */
data class RawPom(
    val groupId: String?,
    val artifactId: String?,
    val version: String?,
    val packaging: String,
    val parent: Coordinate?,
    val properties: Map<String, String>,
    val managed: List<ManagedDep>,
    val dependencies: List<PomDependency>,
    val relocation: PomRelocation? = null,
) {
    /** The coordinate of this POM, falling back to the parent's group/version where the child omits them. */
    fun coordinate(): Coordinate? {
        val g = groupId ?: parent?.group ?: return null
        val a = artifactId ?: return null
        val v = version ?: parent?.version ?: return null
        return Coordinate(g, a, v)
    }
}

/** Parses Maven `.pom` XML into a [RawPom]. DTDs / external entities are disabled (untrusted input). */
object PomParser {

    fun parse(bytes: ByteArray): RawPom {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = dbf.newDocumentBuilder().parse(bytes.inputStream())
        val project = doc.documentElement ?: error("empty pom")

        val parentEl = child(project, "parent")
        val parent = parentEl?.let {
            val g = text(it, "groupId"); val a = text(it, "artifactId"); val v = text(it, "version")
            if (g != null && a != null && v != null) Coordinate(g, a, v) else null
        }

        val properties = child(project, "properties")?.let { props ->
            elements(props).associate { it.tagName to it.textContent.trim() }
        } ?: emptyMap()

        val managed = child(project, "dependencyManagement")
            ?.let { child(it, "dependencies") }
            ?.let { elements(it).filter { e -> e.tagName == "dependency" } }
            ?.map { dep ->
                ManagedDep(
                    ga = GA(text(dep, "groupId") ?: "", text(dep, "artifactId") ?: ""),
                    version = text(dep, "version"),
                    scope = text(dep, "scope"),
                )
            } ?: emptyList()

        val dependencies = child(project, "dependencies")
            ?.let { elements(it).filter { e -> e.tagName == "dependency" } }
            ?.map { dep -> readDependency(dep) }
            ?: emptyList()

        val relocation = child(project, "distributionManagement")
            ?.let { child(it, "relocation") }
            ?.let { PomRelocation(text(it, "groupId"), text(it, "artifactId"), text(it, "version")) }

        return RawPom(
            groupId = text(project, "groupId"),
            artifactId = text(project, "artifactId"),
            version = text(project, "version"),
            packaging = text(project, "packaging") ?: "jar",
            parent = parent,
            properties = properties,
            managed = managed,
            dependencies = dependencies,
            relocation = relocation,
        )
    }

    private fun readDependency(dep: Element): PomDependency {
        val exclusions = child(dep, "exclusions")
            ?.let { elements(it).filter { e -> e.tagName == "exclusion" } }
            ?.map { GA(text(it, "groupId") ?: "", text(it, "artifactId") ?: "") }
            ?.toSet() ?: emptySet()
        return PomDependency(
            groupId = text(dep, "groupId") ?: "",
            artifactId = text(dep, "artifactId") ?: "",
            version = text(dep, "version"),
            scope = text(dep, "scope") ?: "compile",
            optional = text(dep, "optional")?.equals("true", ignoreCase = true) ?: false,
            type = text(dep, "type") ?: "jar",
            classifier = text(dep, "classifier"),
            exclusions = exclusions,
        )
    }

    // --- tiny DOM helpers (first direct child by tag; its trimmed text; child element list) ---

    private fun child(parent: Element, tag: String): Element? =
        elements(parent).firstOrNull { it.tagName == tag }

    private fun text(parent: Element, tag: String): String? =
        child(parent, tag)?.textContent?.trim()?.ifEmpty { null }

    private fun elements(parent: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) out += n as Element
        }
        return out
    }
}

/**
 * Substitutes `${...}` placeholders in [value] from [props], with the artifact's own coordinate fields
 * (`project.version`, `project.groupId`, `version`, …) available too. Iterates a few times so a property
 * defined in terms of another resolves; leaves an unknown placeholder untouched.
 */
fun resolveProperties(value: String?, props: Map<String, String>, self: Coordinate?): String? {
    if (value == null || !value.contains("\${")) return value
    val table = HashMap(props)
    if (self != null) {
        table.putIfAbsent("project.version", self.version)
        table.putIfAbsent("project.groupId", self.group)
        table.putIfAbsent("project.artifactId", self.name)
        table.putIfAbsent("pom.version", self.version)
        table.putIfAbsent("version", self.version)
    }
    var current: String = value
    repeat(8) {
        if (!current.contains("\${")) return current
        var changed = false
        val sb = StringBuilder()
        var i = 0
        while (i < current.length) {
            val start = current.indexOf("\${", i)
            if (start < 0) { sb.append(current.substring(i)); break }
            sb.append(current.substring(i, start))
            val end = current.indexOf('}', start + 2)
            if (end < 0) { sb.append(current.substring(start)); break }
            val key = current.substring(start + 2, end)
            val replacement = table[key]
            if (replacement != null) { sb.append(replacement); changed = true } else sb.append(current.substring(start, end + 1))
            i = end + 1
        }
        current = sb.toString()
        if (!changed) return current
    }
    return current
}
