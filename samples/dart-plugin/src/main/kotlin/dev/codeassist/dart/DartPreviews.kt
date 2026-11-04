package dev.codeassist.dart

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Flutter's `@Preview` widgets (`package:flutter/widget_previews.dart`) in an app's `lib/`, and the gallery
 * app that shows them all, which the preview Run starts in the player and hot-reloads like the app itself.
 *
 * A preview is what Flutter's own previewer takes: a public top-level function, static method, or widget
 * constructor (or factory) with no required arguments, returning a `Widget` or a `WidgetBuilder`, annotated
 * `@Preview(...)`. Its arguments are copied into the gallery as they are written, which is sound because
 * they must be constants and public references.
 */
object DartPreviews {

    /** One preview: where it is, how to call it, its annotation's arguments as written. */
    data class Found(
        /** The library, `package:app/widgets/card.dart`. */
        val library: String,
        /** The call that builds it, without a prefix: `cardPreview()`, `Card.preview()`, `Card()`. */
        val call: String,
        /** What it is called in the gallery when the annotation names nothing. */
        val label: String,
        /** The annotation's arguments, the text between its parentheses. */
        val arguments: String,
        /** It returns a `WidgetBuilder`, to be called with a context. */
        val builder: Boolean,
        /** The line it is declared on, 1-based. */
        val line: Int,
    )

    /** Every preview under [pkg]'s `lib/`, for the package named [name]. */
    fun find(pkg: Path, name: String): List<Found> {
        val lib = pkg.resolve("lib")
        if (!Files.isDirectory(lib)) return emptyList()
        val files = Files.walk(lib).use { w ->
            w.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".dart") }.collect(Collectors.toList())
        }.sorted()
        return files.flatMap { file ->
            val text = runCatching { String(Files.readAllBytes(file)) }.getOrDefault("")
            if (!isLibraryWithPreviews(text)) return@flatMap emptyList()
            val library = "package:$name/" + lib.relativize(file).joinToString("/") { it.toString() }
            scan(text).map { it.copy(library = library) }
        }
    }

    /** Whether [text] can hold previews the gallery can import: it mentions one and is a library, not a `part`. */
    fun isLibraryWithPreviews(text: String): Boolean =
        text.contains("@Preview") && !Regex("""(?m)^\s*part\s+of\b""").containsMatchIn(text)

    /** The previews declared in one library's [text] (their [Found.library] is left blank). */
    fun scan(text: String): List<Found> {
        val classes = classRanges(text)
        val out = ArrayList<Found>()
        var i = 0
        while (i < text.length) {
            val skipped = DartLex.skipNonCode(text, i)
            if (skipped != i) { i = skipped; continue }
            if (text.startsWith("@Preview", i) && !isIdent(text.getOrNull(i + 8)) && !isIdent(text.getOrNull(i - 1))) {
                var k = skipSpace(text, i + 8)
                if (k < text.length && text[k] == '(') {
                    val close = DartLex.matchParen(text, k)
                    val arguments = text.substring(k + 1, close.coerceAtMost(text.length)).trim()
                    declaration(text, close + 1, classes)?.let { (call, label, builder, at) ->
                        out += Found("", call, label, arguments, builder, text.substring(0, at).count { it == '\n' } + 1)
                    }
                    i = close + 1
                    continue
                }
            }
            i++
        }
        return out
    }

    /**
     * The declaration an annotation ending before [from] annotates: past any other annotations, the header up
     * to its parameter list. Null when it is not something a preview can be (private, or not recognisable).
     */
    private fun declaration(text: String, from: Int, classes: List<Pair<String, IntRange>>): Quad? {
        var i = from
        while (true) {
            i = skipSpace(text, i)
            val skipped = DartLex.skipNonCode(text, i)
            if (skipped != i) { i = skipped; continue }
            if (i < text.length && text[i] == '@') {
                // Another annotation: its name, and its arguments if it has any.
                i++
                while (i < text.length && (isIdent(text[i]) || text[i] == '.')) i++
                val k = skipSpace(text, i)
                if (k < text.length && text[k] == '(') i = DartLex.matchParen(text, k) + 1
                continue
            }
            break
        }
        val paren = text.indexOf('(', i).takeIf { it > 0 } ?: return null
        val header = text.substring(i, paren).trim()
        if (header.isEmpty() || header.contains('{') || header.contains(';') || header.contains('=')) return null
        val words = header.split(Regex("\\s+")).filter { it !in MODIFIERS }
        val owner = classes.lastOrNull { (_, range) -> i in range }?.first
        val name = words.lastOrNull() ?: return null
        if (name.split('.').any { it.startsWith("_") }) return null
        return when {
            // A constructor or factory: the class's own name, or its name and a constructor name.
            words.size == 1 && owner != null && name.substringBefore('.') == owner ->
                Quad("$name()", name, false, i)
            words.size >= 2 -> {
                val returnType = words.dropLast(1).joinToString(" ").removeSuffix("?")
                val builder = returnType == "WidgetBuilder"
                if (returnType != "Widget" && !builder) return null
                val isStatic = header.split(Regex("\\s+")).contains("static")
                when {
                    owner == null -> Quad("$name()", name, builder, i)
                    isStatic && !owner.startsWith("_") -> Quad("$owner.$name()", "$owner.$name", builder, i)
                    else -> null // an instance method: not something a preview can be
                }
            }
            else -> null
        }
    }

    private data class Quad(val call: String, val label: String, val builder: Boolean, val at: Int)

    /** Each class (mixin, extension type) declared in [text] and the range of its body. */
    private fun classRanges(text: String): List<Pair<String, IntRange>> {
        val out = ArrayList<Pair<String, IntRange>>()
        val decl = Regex("""\b(?:class|mixin)\s+([A-Za-z_$][A-Za-z0-9_$]*)""")
        var i = 0
        while (i < text.length) {
            val skipped = DartLex.skipNonCode(text, i)
            if (skipped != i) { i = skipped; continue }
            val m = decl.matchAt(text, i)
            if (m != null && !isIdent(text.getOrNull(i - 1))) {
                val open = text.indexOf('{', m.range.last)
                if (open > 0) {
                    out += m.groupValues[1] to (open..DartLex.matchBrace(text, open))
                    i = open + 1
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun skipSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun isIdent(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_' || c == '$')

    private val MODIFIERS = setOf("static", "const", "factory", "external")

    /**
     * The gallery app: every preview, grouped as their annotations say, each sized and themed as Flutter's
     * previewer would (half the screen's height unless the preview gives a size), on a list.
     *
     * [pane] is the editor's preview pane, which has a title of its own, so the gallery drops its app bar.
     * [dark] is the scheme to show it in, when it should not follow the device's. [empty] is what it says
     * when there is nothing to show.
     */
    fun gallery(
        previews: List<Found>,
        pane: Boolean = false,
        dark: Boolean? = null,
        empty: String = "No @Preview widgets in lib/ yet.",
    ): String = buildString {
        appendLine("// Generated by CodeAssist from the app's @Preview widgets. Do not edit: it is rewritten on every save.")
        appendLine("import 'package:flutter/material.dart';")
        appendLine("import 'package:flutter/widget_previews.dart';")
        val libraries = previews.map { it.library }.distinct()
        // Unprefixed, so an annotation's arguments resolve as they did where they were written; prefixed for
        // the calls, so two libraries' previews of the same name do not collide.
        libraries.forEachIndexed { n, lib -> appendLine("import ${quote(lib)};"); appendLine("import ${quote(lib)} as p$n;") }
        appendLine()
        // A getter, not a final: a hot reload keeps a global's value once it has been read, so a list built once
        // would show the previews as they were when the gallery started.
        appendLine("List<_Entry> get _previews => <_Entry>[")
        for (p in previews) {
            val prefix = "p${libraries.indexOf(p.library)}"
            val build = if (p.builder) "(context) => $prefix.${p.call}(context)" else "(context) => $prefix.${p.call}"
            appendLine("  _Entry(${quote(p.label)}, ${quote(p.library.substringAfter('/'))}, ${p.line}, const Preview(${p.arguments}), $build),")
        }
        appendLine("];")
        // Getters for the same reason: a hot reload has to see these change.
        appendLine("bool get _inPane => $pane;")
        appendLine("Brightness? get _brightness => ${when (dark) { true -> "Brightness.dark"; false -> "Brightness.light"; null -> "null" }};")
        appendLine("String get _empty => ${quote(empty)};")
        append(GALLERY)
    }

    /** Write a gallery's [source] to [file] unless it already holds exactly that. Answers whether it changed. */
    fun write(file: Path, source: String): Boolean {
        Files.createDirectories(file.parent)
        val before = runCatching { String(Files.readAllBytes(file)) }.getOrNull()
        if (before == source) return false
        Files.write(file, source.toByteArray())
        return true
    }

    private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("$", "\\$") + "'"

    private val GALLERY = """

        void main() => runApp(const _Gallery());

        class _Entry {
          const _Entry(this.label, this.file, this.line, this.preview, this.build);
          final String label;
          final String file;
          final int line;
          final Preview preview;
          final WidgetBuilder build;
        }

        class _Gallery extends StatelessWidget {
          const _Gallery();

          @override
          Widget build(BuildContext context) {
            final groups = <String, List<_Entry>>{};
            for (final e in _previews) {
              groups.putIfAbsent(e.preview.group, () => <_Entry>[]).add(e);
            }
            return MaterialApp(
              debugShowCheckedModeBanner: false,
              title: 'Previews',
              theme: ThemeData(colorSchemeSeed: Colors.blueGrey, brightness: _brightness ?? MediaQuery.platformBrightnessOf(context)),
              home: Scaffold(
                appBar: _inPane ? null : AppBar(title: Text('Previews (${'$'}{_previews.length})')),
                body: _previews.isEmpty
                    ? Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(_empty, textAlign: TextAlign.center)))
                    : ListView(
                        padding: const EdgeInsets.all(12),
                        children: [
                          for (final group in groups.entries) ...[
                            if (groups.length > 1)
                              Padding(
                                padding: const EdgeInsets.fromLTRB(4, 12, 4, 4),
                                child: Text(group.key, style: Theme.of(context).textTheme.titleMedium),
                              ),
                            for (final e in group.value) _PreviewCard(entry: e),
                          ],
                        ],
                      ),
              ),
            );
          }
        }

        class _PreviewCard extends StatelessWidget {
          const _PreviewCard({required this.entry});
          final _Entry entry;

          @override
          Widget build(BuildContext context) {
            final preview = entry.preview;
            final screen = MediaQuery.sizeOf(context);
            Widget child = Builder(builder: entry.build);
            if (preview.wrapper != null) child = preview.wrapper!(child);
            if (preview.theme != null) {
              final theme = preview.theme!();
              child = Builder(builder: (context) => theme.apply(context, child));
            }
            final media = MediaQuery.of(context);
            child = MediaQuery(
              data: media.copyWith(
                platformBrightness: preview.brightness ?? _brightness ?? media.platformBrightness,
                textScaler: preview.textScaleFactor == null ? media.textScaler : TextScaler.linear(preview.textScaleFactor!),
              ),
              child: child,
            );
            final size = preview.size;
            final width = size?.width == double.infinity ? null : size?.width;
            final height = size?.height == double.infinity ? null : (size?.height ?? screen.height / 2);
            return Card(
              clipBehavior: Clip.antiAlias,
              margin: const EdgeInsets.symmetric(vertical: 6),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  ListTile(
                    dense: true,
                    title: Text(preview.name ?? entry.label),
                    subtitle: Text('${'$'}{entry.file}:${'$'}{entry.line}'),
                  ),
                  ClipRect(child: Center(child: SizedBox(width: width, height: height, child: child))),
                ],
              ),
            );
          }
        }
    """.trimIndent() + "\n"
}
