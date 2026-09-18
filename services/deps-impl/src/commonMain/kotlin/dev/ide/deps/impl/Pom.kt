package dev.ide.deps.impl

import dev.ide.model.Coordinate

/** A `group:name` identity, the granularity at which Maven dedups and excludes. */
data class GA(val group: String, val name: String) {
    override fun toString() = "$group:$name"
}

/** A `<dependency>` entry as written in a POM (version/scope may be null → filled from dependencyManagement). */
data class PomDependency(
    val groupId: String,
    val artifactId: String,
    val version: String?,
    /**
     * `compile` | `runtime` | `provided` | `test` | `system`, or null when the POM omitted it. Null is NOT
     * "compile": dependencyManagement supplies the scope for an entry that declares none (Maven's rule), and
     * `compile` is only the default once nothing manages the coordinate either.
     */
    val scope: String?,
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

/**
 * Parses Maven `.pom` XML into a [RawPom].
 *
 * Reads through [Xml], which does not support DTDs at all — the defence the JVM parser this replaces had to
 * switch on feature by feature, since a POM is untrusted input fetched off the network.
 */
object PomParser {

    fun parse(bytes: ByteArray): RawPom {
        val project = Xml.parse(bytes)

        val parent = project.child("parent")?.let {
            val g = it.childText("groupId"); val a = it.childText("artifactId"); val v = it.childText("version")
            if (g != null && a != null && v != null) Coordinate(g, a, v) else null
        }

        val properties = project.child("properties")
            ?.children?.associate { it.tag to it.text }
            ?: emptyMap()

        val managed = project.child("dependencyManagement")
            ?.child("dependencies")
            ?.childrenNamed("dependency")
            ?.map { dep ->
                ManagedDep(
                    ga = GA(dep.childText("groupId") ?: "", dep.childText("artifactId") ?: ""),
                    version = dep.childText("version"),
                    scope = dep.childText("scope"),
                )
            } ?: emptyList()

        val dependencies = project.child("dependencies")
            ?.childrenNamed("dependency")
            ?.map { dep -> readDependency(dep) }
            ?: emptyList()

        val relocation = project.child("distributionManagement")
            ?.child("relocation")
            ?.let { PomRelocation(it.childText("groupId"), it.childText("artifactId"), it.childText("version")) }

        return RawPom(
            groupId = project.childText("groupId"),
            artifactId = project.childText("artifactId"),
            version = project.childText("version"),
            packaging = project.childText("packaging") ?: "jar",
            parent = parent,
            properties = properties,
            managed = managed,
            dependencies = dependencies,
            relocation = relocation,
        )
    }

    private fun readDependency(dep: XmlElement): PomDependency {
        val exclusions = dep.child("exclusions")
            ?.childrenNamed("exclusion")
            ?.map { GA(it.childText("groupId") ?: "", it.childText("artifactId") ?: "") }
            ?.toSet() ?: emptySet()
        return PomDependency(
            groupId = dep.childText("groupId") ?: "",
            artifactId = dep.childText("artifactId") ?: "",
            version = dep.childText("version"),
            scope = dep.childText("scope"),   // absent → let dependencyManagement decide (see [PomDependency.scope])
            optional = dep.childText("optional")?.equals("true", ignoreCase = true) ?: false,
            type = dep.childText("type") ?: "jar",
            classifier = dep.childText("classifier"),
            exclusions = exclusions,
        )
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
        // A POM's own properties win over these built-ins, so they only fill what is absent.
        table.getOrPut("project.version") { self.version }
        table.getOrPut("project.groupId") { self.group }
        table.getOrPut("project.artifactId") { self.name }
        table.getOrPut("pom.version") { self.version }
        table.getOrPut("version") { self.version }
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
