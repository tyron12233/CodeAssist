package dev.ide.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The text a user shares when the editor flags code that is correct ("Report a false error" on a diagnostic).
 *
 * A false error is almost always decided by something the screenshot does not show: which platform the module
 * resolved, what is on its classpath, and above all WHICH entry a class was taken from when more than one
 * declares it (a test library that carries its own older `java.nio.file.Files` hides the platform's newer
 * methods, and the editor reports them unresolved). So the report states those facts, plus the flagged file,
 * in one Markdown document the user can read before sending and paste into an issue as it is.
 *
 * Pure formatting over values the caller gathered, so it is tested without a project.
 */
internal object FalseErrorReport {

    /** One classpath entry: where it is, what kind it is, and (for a file) how big. */
    class Entry(val path: Path, val kind: String)

    class Dependency(val scope: String, val name: String, val exported: Boolean)

    class Input(
        val appVersion: String?,
        val host: String,
        val severity: String,
        val message: String,
        val file: Path,
        val text: String,
        val startOffset: Int,
        val endOffset: Int,
        val language: String,
        val projectRoot: Path?,
        val moduleName: String?,
        val moduleType: String?,
        val languageLevel: String?,
        val sourceSet: String?,
        val platformSdk: String?,
        /** The module's own settings that bear on resolution (compileSdk, minSdk, variant, ...), in order. */
        val moduleFacts: List<Pair<String, String>>,
        val indexState: String,
        val boot: List<Entry>,
        val classpath: List<Entry>,
        val dependencies: List<Dependency>,
        val recentLog: List<String>,
    )

    fun render(input: Input): String = buildString {
        val shorten = PathShortener(input.projectRoot)
        append("# CodeAssist false error report\n\n")
        append("- App version: ").append(input.appVersion ?: "unknown").append('\n')
        append("- Host: ").append(input.host).append('\n')
        append("- Index: ").append(input.indexState).append("\n\n")

        append("## Diagnostic\n\n")
        val (line, col, lineText) = locate(input.text, input.startOffset)
        append("- Severity: ").append(input.severity).append('\n')
        append("- Message: ").append(input.message.replace('\n', ' ')).append('\n')
        append("- At: line ").append(line + 1).append(", column ").append(col + 1).append('\n')
        val flagged = input.text.substring(
            input.startOffset.coerceIn(0, input.text.length), input.endOffset.coerceIn(input.startOffset.coerceIn(0, input.text.length), input.text.length),
        ).lineSequence().firstOrNull().orEmpty()
        append("- Flagged text: `").append(flagged.take(120)).append("`\n\n")
        append("```\n").append(lineText).append('\n')
        append(" ".repeat(col)).append("^".repeat(flagged.length.coerceIn(1, (lineText.length - col).coerceAtLeast(1)))).append("\n```\n\n")

        append("## File and module\n\n")
        append("- File: ").append(shorten(input.file)).append(" (").append(input.language).append(")\n")
        append("- Source set: ").append(input.sourceSet ?: "none").append('\n')
        append("- Module: ").append(input.moduleName ?: "none").append(input.moduleType?.let { " ($it)" } ?: "").append('\n')
        append("- Language level: ").append(input.languageLevel ?: "unknown").append('\n')
        append("- Platform SDK: ").append(input.platformSdk ?: "none resolved").append('\n')
        for ((k, v) in input.moduleFacts) append("- ").append(k).append(": ").append(v).append('\n')
        append('\n')

        // The section that usually holds the answer: every class the file imports, and each place it is declared.
        val all = input.boot + input.classpath
        val imports = importedNames(input.text)
        val declared = declaringEntries(imports, all)
        val shadowed = declared.filterValues { it.size > 1 }
        append("## Imported classes and where they are declared\n\n")
        if (shadowed.isNotEmpty()) {
            append("**Declared by more than one entry (the editor may be reading the wrong one):**\n\n")
            for ((name, entries) in shadowed) {
                append("- `").append(name).append("`\n")
                entries.forEach { append("  - ").append(it.kind).append(": ").append(shorten(it.path)).append('\n') }
            }
            append('\n')
        }
        if (imports.isEmpty()) append("The file has no imports.\n")
        for (name in imports) {
            val entries = declared[name].orEmpty()
            append("- `").append(name).append("`: ")
            append(
                when {
                    entries.isEmpty() -> "not found in any jar or class directory (project source, a class of a modular JDK, or missing)"
                    else -> entries.joinToString("; ") { "${it.kind} ${shorten(it.path)}" }
                },
            ).append('\n')
        }
        append('\n')

        append("## Declared dependencies (").append(input.dependencies.size).append(")\n\n")
        input.dependencies.forEach { d ->
            append("- ").append(d.scope).append(": ").append(d.name).append(if (d.exported) " (exported)" else "").append('\n')
        }
        append('\n')

        entries("Platform (boot classpath)", input.boot, shorten)
        entries("Classpath", input.classpath, shorten)

        append("## Recent log (").append(input.recentLog.size).append(" lines)\n\n```\n")
        input.recentLog.forEach { append(shorten.text(it)).append('\n') }
        append("```\n\n")

        append("## Source\n\n```").append(input.language.lowercase()).append('\n')
        append(input.text)
        if (!input.text.endsWith("\n")) append('\n')
        append("```\n")
    }

    private fun StringBuilder.entries(title: String, list: List<Entry>, shorten: PathShortener) {
        append("## ").append(title).append(" (").append(list.size).append(")\n\n")
        for (e in list) {
            val size = runCatching { if (Files.isRegularFile(e.path)) " (${Files.size(e.path)} bytes)" else if (Files.isDirectory(e.path)) " (directory)" else " (MISSING)" }.getOrDefault("")
            append("- ").append(e.kind).append(": ").append(shorten(e.path)).append(size).append('\n')
        }
        append('\n')
    }

    /** The 0-based line and column of [offset] in [text], and that line's text. */
    internal fun locate(text: String, offset: Int): Triple<Int, Int, String> {
        val at = offset.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        val line = text.subSequence(0, lineStart).count { it == '\n' }
        return Triple(line, at - lineStart, text.substring(lineStart, lineEnd))
    }

    /** The names a Java or Kotlin file imports, in order, without `static`, an alias or a trailing `.*`. */
    internal fun importedNames(text: String): List<String> =
        IMPORT.findAll(text).map { it.groupValues[1] }.distinct().take(MAX_IMPORTS).toList()

    /**
     * Which of [entries] declare each of [names]. An import can name a class, a nested class or a member, and a
     * jar only knows class files, so every way of splitting the name into package / class / member is tried,
     * longest class first: `a.b.Outer.Inner` is looked up as `a/b/Outer$Inner`, then as `a/b/Outer`.
     */
    internal fun declaringEntries(names: List<String>, entries: List<Entry>): Map<String, List<Entry>> {
        val out = LinkedHashMap<String, MutableList<Entry>>()
        if (names.isEmpty()) return out
        val candidates = names.associateWith(::classFileCandidates)
        for (entry in entries) {
            val has: (String) -> Boolean = when {
                Files.isDirectory(entry.path) -> { rel -> Files.isRegularFile(entry.path.resolve(rel)) }
                Files.isRegularFile(entry.path) -> {
                    val zip = runCatching { ZipFile(entry.path.toFile()) }.getOrNull() ?: continue
                    try {
                        for ((name, files) in candidates) if (files.any { zip.getEntry(it) != null }) out.getOrPut(name) { ArrayList() }.add(entry)
                    } finally {
                        runCatching { zip.close() }
                    }
                    continue
                }
                else -> continue
            }
            for ((name, files) in candidates) if (files.any(has)) out.getOrPut(name) { ArrayList() }.add(entry)
        }
        return out
    }

    internal fun classFileCandidates(name: String): List<String> {
        val parts = name.split('.')
        val out = ArrayList<String>()
        // `classEnd` = how many leading segments are package + class (the rest would be a member).
        for (classEnd in parts.size downTo maxOf(1, parts.size - 1)) {
            for (packageEnd in classEnd - 1 downTo 0) {
                val pkg = parts.subList(0, packageEnd).joinToString("/")
                val cls = parts.subList(packageEnd, classEnd).joinToString("$")
                out += (if (pkg.isEmpty()) cls else "$pkg/$cls") + ".class"
            }
        }
        return out
    }

    /** Paths as the reader needs them and no more: under the project, under the home directory, or by their tail. */
    internal class PathShortener(projectRoot: Path?) {
        private val project = projectRoot?.toAbsolutePath()?.normalize()?.toString()
        private val home = System.getProperty("user.home")?.takeIf { it.length > 1 }

        operator fun invoke(path: Path): String = text(path.toAbsolutePath().normalize().toString())

        fun text(s: String): String {
            var out = s
            if (project != null) out = out.replace(project, "<project>")
            if (home != null) out = out.replace(home, "~")
            return out
        }
    }

    private val IMPORT = Regex("""(?m)^\s*import\s+(?:static\s+)?([A-Za-z_][\w.]*?)(?:\.\*)?\s*(?:as\s+\w+\s*)?;?\s*$""")
    private const val MAX_IMPORTS = 200
}

/** What a report says about where the IDE is running; kept apart from the pure renderer because it reads the host. */
internal object FalseErrorReports {

    /** How many of the most recent log records a report carries. */
    const val LOG_LINES = 60

    fun hostDescription(): String {
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"
        val vm = "${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}"
        // On Android the framework says more than the VM properties do; read reflectively (this module is plain JVM).
        val android = runCatching {
            val api = Class.forName("android.os.Build\$VERSION").getField("SDK_INT").getInt(null)
            val build = Class.forName("android.os.Build")
            "Android API $api, ${build.getField("MANUFACTURER").get(null)} ${build.getField("MODEL").get(null)}"
        }.getOrNull()
        return listOfNotNull(android, os, vm).joinToString("; ")
    }
}
