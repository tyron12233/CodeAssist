package dev.codeassist.swift

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * What a SwiftUI preview compiles: the file being previewed and the rest of its target, rewritten so CodeAssist's
 * SwiftUI engine can run their previews without macros, plus a `main.swift` that runs them.
 *
 * `#Preview` is a macro, and macros need a compiler plugin the device does not have, so each `#Preview(...) { ... }`
 * becomes `let __preview_<file>_<n> = _Preview(...) { ... }`, the engine's equivalent, on the same line: every line
 * keeps its number, so the compiler's messages point at the user's lines. `PreviewProvider` types are found and run as
 * they are. `@Previewable` (a macro attribute) is dropped, leaving the plain property wrapper. `@main` is blanked: the
 * generated `main.swift` is the entry point.
 */
object SwiftPreviewSource {

    data class Rewritten(val text: String, val previews: List<String>, val providers: List<String>)

    data class Build(
        /** The files to compile, generated copies included, in a stable order; `main.swift` last. */
        val files: List<Path>,
        /** Generated copy -> the file it stands for, to put compiler messages back on the user's file. */
        val origins: Map<Path, Path>,
        val previewCount: Int,
    )

    /** Whether [text] declares a preview at all (cheap: no rewriting). */
    fun hasPreviews(text: String): Boolean = "#Preview" in text || PROVIDER.containsMatchIn(text)

    private val PROVIDER = Regex("""\b(?:struct|class|enum)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?:[A-Za-z_.]+\s*,\s*)*PreviewProvider\b""")

    fun rewrite(text: String, fileTag: String): Rewritten {
        val out = StringBuilder(text.length + 64)
        val previews = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val skipped = SwiftLex.skipNonCode(text, i)
            if (skipped != i) {
                out.append(text, i, skipped)
                i = skipped
                continue
            }
            val c = text[i]
            val atWordStart = i == 0 || !isIdent(text[i - 1])
            when {
                c == '#' && text.startsWith("#Preview", i) && !isIdent(text.getOrElse(i + 8) { ' ' }) -> {
                    val name = "__preview_${fileTag}_${previews.size}"
                    previews += name
                    out.append("let ").append(name).append(" = _Preview")
                    i += "#Preview".length
                }
                c == '@' && text.startsWith("@Previewable", i) && !isIdent(text.getOrElse(i + 12) { ' ' }) -> {
                    out.append(" ".repeat(12))
                    i += 12
                }
                c == '@' && atWordStart && text.startsWith("@main", i) && !isIdent(text.getOrElse(i + 5) { ' ' }) -> {
                    out.append("     ")
                    i += 5
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        val providers = PROVIDER.findAll(blankNonCode(text)).map { it.groupValues[1] }.toList()
        return Rewritten(out.toString(), previews, providers)
    }

    /**
     * Lay out a preview build of [file] (whose live text is [text]) in [outDir]: every `.swift` file of its target,
     * rewritten, and the `main.swift` that runs [file]'s previews. A file outside a Swift package is a target of its
     * own.
     */
    fun prepare(file: Path, text: String, outDir: Path): Build {
        val target = targetDir(file)
        val siblings = if (target == null) listOf(file) else swiftFiles(target)
        Files.createDirectories(outDir)
        // Leftovers of files that were renamed or deleted would still be compiled.
        Files.list(outDir).use { old -> old.filter { it.fileName.toString().endsWith(".swift") }.forEach { Files.deleteIfExists(it) } }

        val files = ArrayList<Path>()
        val origins = HashMap<Path, Path>()
        var runPreviews = emptyList<String>()
        var runProviders = emptyList<String>()
        for ((index, source) in siblings.withIndex()) {
            // A target's main.swift is a program: its top-level code would run, and it may not coexist with ours.
            if (source.fileName.toString() == "main.swift" && source != file) continue
            val sourceText = if (source == file) text else runCatching { String(Files.readAllBytes(source)) }.getOrNull() ?: continue
            val rewritten = rewrite(sourceText, "f$index")
            val name = if (source.fileName.toString() == "main.swift") "main_.swift" else source.fileName.toString()
            val copy = outDir.resolve("${index}_$name")
            Files.write(copy, rewritten.text.toByteArray())
            files.add(copy)
            origins[copy] = source
            if (source == file) {
                runPreviews = rewritten.previews
                runProviders = rewritten.providers
            }
        }
        val main = outDir.resolve("main.swift")
        val all = runPreviews + runProviders.map { "_Preview(provider: $it.self)" }
        Files.write(main, mainSource(all).toByteArray())
        files.add(main)
        return Build(files, origins, all.size)
    }

    /** The `@main` App of [text], by name: `@main struct MyApp: App`. */
    fun appType(text: String): String? = APP_MAIN.find(text)?.groupValues?.get(1)

    private val APP_MAIN = Regex("""@main\s+(?:(?:public|internal|final)\s+)*struct\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?:[A-Za-z_.]+\s*,\s*)*(?:SwiftUI\.)?App\b""")

    /**
     * The target at [target] as an app: every file, rewritten as for a preview (#Preview declares a preview, `@main`
     * goes), and a main.swift that runs the `@main` App full screen. Null when no file declares one.
     */
    fun prepareApp(target: Path, outDir: Path): Build? {
        val sources = swiftFiles(target).filter { it.fileName.toString() != "main.swift" }
        Files.createDirectories(outDir)
        Files.list(outDir).use { old -> old.filter { it.fileName.toString().endsWith(".swift") }.forEach { Files.deleteIfExists(it) } }
        val files = ArrayList<Path>()
        val origins = HashMap<Path, Path>()
        var app: String? = null
        for ((index, source) in sources.withIndex()) {
            val text = String(Files.readAllBytes(source))
            app = app ?: appType(text)
            val copy = outDir.resolve("${index}_${source.fileName}")
            Files.write(copy, rewrite(text, "f$index").text.toByteArray())
            files.add(copy)
            origins[copy] = source
        }
        val type = app ?: return null
        val main = outDir.resolve("main.swift")
        Files.write(main, "import SwiftUI\n\nMainActor.assumeIsolated {\n    _runApp($type.self)\n}\n".toByteArray())
        files.add(main)
        return Build(files, origins, 1)
    }

    fun mainSource(previews: List<String>): String = buildString {
        append("import SwiftUI\n\n")
        append("MainActor.assumeIsolated {\n")
        append("    _runPreviews([").append(previews.joinToString(", ")).append("])\n")
        append("}\n")
    }

    /** `Sources/<Target>/` above [file], or null when the file is not in a SwiftPM target. */
    fun targetDir(file: Path): Path? {
        var dir: Path? = file.parent
        while (dir != null) {
            val parent = dir.parent ?: return null
            if (parent.fileName?.toString() == SwiftPackages.SOURCES && Files.isRegularFile(parent.parent?.resolve(SwiftPackages.MANIFEST) ?: return null)) {
                return dir
            }
            dir = parent
        }
        return null
    }

    /** The package directory (where `Package.swift` is) above [file], if any. */
    fun packageDir(file: Path): Path? = targetDir(file)?.parent?.parent

    fun swiftFiles(dir: Path): List<Path> = Files.walk(dir).use { s ->
        s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".swift") }.sorted().collect(Collectors.toList())
    }

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

    /** [text] with comments and string contents replaced by spaces (same length), for regex scans of code only. */
    private fun blankNonCode(text: String): String {
        val sb = StringBuilder(text)
        var i = 0
        while (i < text.length) {
            val end = SwiftLex.skipNonCode(text, i)
            if (end != i) {
                for (k in i until end) if (sb[k] != '\n') sb.setCharAt(k, ' ')
                i = end
            } else i++
        }
        return sb.toString()
    }
}

/** Just enough of Swift's lexical structure to step over comments and string literals. */
internal object SwiftLex {
    /** Past a comment or string literal starting at [i], or [i] itself when neither starts there. */
    fun skipNonCode(text: String, i: Int): Int {
        if (text.startsWith("//", i)) return text.indexOf('\n', i).let { if (it < 0) text.length else it }
        if (text.startsWith("/" + "*", i)) {
            // Swift block comments nest.
            var depth = 0
            var j = i
            while (j < text.length) {
                if (text.startsWith("/" + "*", j)) { depth++; j += 2; continue }
                if (text.startsWith("*" + "/", j)) { depth--; j += 2; if (depth == 0) return j; continue }
                j++
            }
            return text.length
        }
        // Raw strings: #"..."#, ##"..."##, and their multi-line forms.
        var hashes = 0
        while (i + hashes < text.length && text[i + hashes] == '#') hashes++
        val q = i + hashes
        if (q >= text.length || text[q] != '"') return i
        val multiline = text.startsWith("\"\"\"", q)
        val close = (if (multiline) "\"\"\"" else "\"") + "#".repeat(hashes)
        var j = q + if (multiline) 3 else 1
        while (j < text.length) {
            if (hashes == 0 && text[j] == '\\') {
                // An interpolation \( ... ) may itself hold strings and parentheses.
                if (j + 1 < text.length && text[j + 1] == '(') {
                    j = skipInterpolation(text, j + 2)
                    continue
                }
                j += 2
                continue
            }
            if (text.startsWith(close, j)) return j + close.length
            if (!multiline && text[j] == '\n') return j
            j++
        }
        return text.length
    }

    private fun skipInterpolation(text: String, start: Int): Int {
        var depth = 1
        var j = start
        while (j < text.length && depth > 0) {
            val skipped = skipNonCode(text, j)
            if (skipped != j) { j = skipped; continue }
            when (text[j]) {
                '(' -> depth++
                ')' -> depth--
            }
            j++
        }
        return j
    }
}
