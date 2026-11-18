package dev.codeassist.csharp.project.csproj

import dev.codeassist.csharp.project.csproj.CsprojText.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

/**
 * Checks a project file for issues relevant to the editor:
 * invalid or unsupported values, missing referenced files, and settings this plugin does not honour.
 *
 * Checks are performed against [CsprojText], so every finding has a source range.
 * Only definite problems are reported. Unknown properties and elements are left alone,
 * and values containing MSBuild expressions (`$(...)`) are not validated.
 */
internal object CsprojCheck {

    enum class Level { ERROR, WARNING }

    class Finding(
        val start: Int,
        val end: Int,
        val level: Level,
        val message: String,
        val code: String,
    )

    /** Project context used to check referenced paths and entry points. */
    class Env(
        /** Checks a path as written in the project, relative to its folder. */
        val exists: (String) -> Boolean,
        /** Classes containing a `Main` method in the project sources. */
        val mainClasses: List<String>,
        val hasTopLevelStatements: Boolean,
    )

    private val FRAMEWORK =
        Regex("""net\d+(\.\d+)?(-[\w.]+)?|netcoreapp\d+\.\d+|netstandard\d+\.\d+|net4\d+""")

    private val LANG_VERSION = Regex("""\d+(\.\d+)?""")
    private val LANG_WORDS = setOf("latest", "latestmajor", "preview", "default")

    fun check(text: String, env: Env): List<Finding> {
        wellFormed(text)?.let { return listOf(it) }

        val elements = CsprojText.elements(text)
        val out = ArrayList<Finding>()

        for (el in elements) {
            when (el.parent) {
                "PropertyGroup" -> property(el, out, env)
                "ItemGroup" -> item(el, elements, out, env)
            }
        }

        duplicatePackages(elements, out)
        return out
    }

    /**
     * Returns the XML parser's error as a finding on the affected line,
     * or null when the document is well formed.
     */
    fun wellFormed(text: String): Finding? {
        val factory = DocumentBuilderFactory.newInstance()
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }

        return try {
            val builder = factory.newDocumentBuilder()
            builder.setErrorHandler(null)
            builder.parse(InputSource(StringReader(text)))
            null
        } catch (e: SAXParseException) {
            val start = offsetOfLine(text, e.lineNumber)
            val lineEnd = text.indexOf('\n', start).let {
                if (it < 0) text.length else it
            }

            Finding(
                start,
                lineEnd.coerceAtLeast(start + 1).coerceAtMost(maxOf(text.length, start + 1)),
                Level.ERROR,
                "Not well-formed XML: ${e.message?.substringAfter(": ")?.trim().orEmpty()}",
                "csproj.xml",
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun property(el: CsprojText.Element, out: MutableList<Finding>, env: Env) {
        val value = value(el)
        if (value.isEmpty() || "$(" in value) return

        val (start, end) = valueRange(el)

        fun bad(message: String) {
            out += Finding(start, end, Level.ERROR, message, "csproj.value")
        }

        if (el.name == "StartupObject") {
            return startupObject(value, start, end, out, env)
        }

        val property = CsprojSchema.property(el.name) ?: return

        when (property.kind) {
            CsprojSchema.Kind.ENUM, CsprojSchema.Kind.BOOLEAN ->
                if (property.values.none { it.equals(value, ignoreCase = true) }) {
                    bad("${property.name} is ${property.values.joinToString(", ")}, not '$value'")
                }

            CsprojSchema.Kind.INTEGER ->
                if (value.toIntOrNull() !in 0..9) {
                    bad("${property.name} is a number from 0 to 9, not '$value'")
                }

            CsprojSchema.Kind.LANG_VERSION ->
                if (value.lowercase() !in LANG_WORDS && !LANG_VERSION.matches(value)) {
                    bad("LangVersion is latest, preview, default or a number such as 12, not '$value'")
                }

            CsprojSchema.Kind.FRAMEWORK ->
                if (!FRAMEWORK.matches(value)) {
                    out += Finding(
                        start,
                        end,
                        Level.WARNING,
                        "Unrecognised target framework '$value'",
                        "csproj.value",
                    )
                }

            CsprojSchema.Kind.TEXT -> Unit
        }
    }

    private fun startupObject(
        value: String,
        start: Int,
        end: Int,
        out: MutableList<Finding>,
        env: Env,
    ) {
        when {
            env.hasTopLevelStatements -> out += Finding(
                start,
                end,
                Level.WARNING,
                "StartupObject is ignored: a file has top-level statements, which are the entry point",
                "csproj.startup",
            )

            env.mainClasses.isNotEmpty() && value !in env.mainClasses -> out += Finding(
                start,
                end,
                Level.WARNING,
                "No static Main in '$value' (found: ${env.mainClasses.joinToString()})",
                "csproj.startup",
            )
        }
    }

    private fun item(
        el: CsprojText.Element,
        all: List<CsprojText.Element>,
        out: MutableList<Finding>,
        env: Env,
    ) {
        val include = el.attr("Include")

        fun warn(start: Int, end: Int, message: String, code: String) {
            out += Finding(start, end, Level.WARNING, message, code)
        }

        when (el.name) {
            "PackageReference" -> {
                if (include == null && el.attr("Update") == null) {
                    out += Finding(
                        el.nameStart,
                        el.nameEnd,
                        Level.ERROR,
                        "PackageReference needs an Include",
                        "csproj.item",
                    )
                } else if (include != null && include.value.isBlank()) {
                    out += Finding(
                        include.valueStart,
                        include.valueEnd + 1,
                        Level.ERROR,
                        "Empty package id",
                        "csproj.item",
                    )
                } else if (
                    include != null &&
                    el.attr("Version") == null &&
                    child(el, all, "Version") == null
                ) {
                    warn(
                        el.nameStart,
                        el.nameEnd,
                        "No Version: the restore is asked for the package with none",
                        "csproj.item",
                    )
                }
            }

            "ProjectReference" -> if (
                include != null &&
                include.value.isNotBlank() &&
                "$(" !in include.value &&
                !env.exists(include.value.replace('\\', '/'))
            ) {
                warn(
                    include.valueStart,
                    include.valueEnd,
                    "Project not found: ${include.value}",
                    "csproj.item",
                )
            }

            "Reference" -> {
                val hint = el.attr("HintPath")?.let {
                    it.value to (it.valueStart to it.valueEnd)
                } ?: child(el, all, "HintPath")?.let {
                    it.text.trim() to (it.textStart to it.textEnd)
                }

                if (
                    hint != null &&
                    hint.first.isNotEmpty() &&
                    "$(" !in hint.first &&
                    !env.exists(hint.first)
                ) {
                    warn(
                        hint.second.first,
                        hint.second.second,
                        "HintPath not found: ${hint.first}",
                        "csproj.item",
                    )
                }
            }

            "Compile" -> include?.let {
                val path = it.value
                val literal = path.none { c -> c in "*?" } &&
                    "$(" !in path &&
                    ';' !in path &&
                    path.isNotBlank()

                if (literal && !env.exists(path)) {
                    warn(it.valueStart, it.valueEnd, "File not found: $path", "csproj.item")
                }
            }

            "Using" -> if (include == null && el.attr("Remove") == null) {
                warn(
                    el.nameStart,
                    el.nameEnd,
                    "Using needs an Include or a Remove",
                    "csproj.item",
                )
            }
        }
    }

    /**
     * Finds an element named [name] inside [parent], such as `<Version>`
     * inside `<PackageReference>`.
     */
    private fun child(
        parent: CsprojText.Element,
        all: List<CsprojText.Element>,
        name: String,
    ): CsprojText.Element? {
        val end = parent.textEnd
        if (parent.selfClosing) return null

        // The scan does not record closing tags. Find the matching child before the next sibling.
        val index = all.indexOf(parent)

        for (i in index + 1 until all.size) {
            val candidate = all[i]

            if (candidate.parent != parent.name) {
                if (candidate.start > end && candidate.parent == parent.parent) return null
                continue
            }

            if (candidate.name == name && candidate.start >= parent.textStart) return candidate
            if (candidate.name == parent.name) return null
        }

        return null
    }

    private fun duplicatePackages(elements: List<CsprojText.Element>, out: MutableList<Finding>) {
        val seen = HashSet<String>()

        for (el in elements) {
            if (el.name != "PackageReference" || el.parent != "ItemGroup") continue

            val include = el.attr("Include") ?: continue
            if (include.value.isBlank() || "$(" in include.value) continue

            if (!seen.add(include.value.lowercase())) {
                out += Finding(
                    include.valueStart,
                    include.valueEnd,
                    Level.WARNING,
                    "${include.value} is referenced more than once",
                    "csproj.item",
                )
            }
        }
    }

    private fun value(element: CsprojText.Element) = element.text.trim()

    private fun valueRange(element: CsprojText.Element): Pair<Int, Int> {
        val lead = element.text.length - element.text.trimStart().length
        return (element.textStart + lead) to (element.textStart + lead + value(element).length)
    }

    private fun offsetOfLine(text: String, line: Int): Int {
        var offset = 0

        repeat((line - 1).coerceAtLeast(0)) {
            val next = text.indexOf('\n', offset)
            if (next < 0) return text.length
            offset = next + 1
        }

        return offset
    }
}