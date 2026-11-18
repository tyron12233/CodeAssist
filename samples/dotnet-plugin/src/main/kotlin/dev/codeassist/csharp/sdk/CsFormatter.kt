package dev.codeassist.csharp.sdk

import android.util.Log
import dev.codeassist.csharp.util.TAG
import dev.codeassist.csharp.util.ToolCancellation

/**
 * Reformatting, by Roslyn's own formatter in the resident server ([CsServer]).
 *
 * The request is `@@format`, the start and end of the range to format (both `-1` for the whole file), the
 * buffer's path, then the compiler's arguments. The reply is one `edit <offset> <length> <text>` line per
 * change, offsets being those of the buffer as sent and the text escaped (see [CsFormatEdits]).
 *
 * The formatter's assemblies are loaded when the first request arrives and not before: they are several
 * megabytes that a session which never reformats does not pay for.
 */
internal object CsFormatter {

    private const val FAILED = 255

    fun format(
        sdk: DotnetSdk,
        bufferPath: String,
        start: Int,
        end: Int,
        indentSize: Int,
        useTabs: Boolean,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? {
        val request = listOf("@@format", start.toString(), end.toString(), indentSize.toString(), if (useTabs) "1" else "0", bufferPath) +
            arguments
        val exitCode = CsServer.compile(sdk, request, cancellation, onLine) ?: return null
        if (exitCode == FAILED) {
            Log.d(TAG, "@@format: the program failed inside; see the lines above")
            return null
        }
        return exitCode
    }
}

/** Reads the `edit` lines of a `@@format` reply. Pure, so it can be tested without a device. */
internal object CsFormatEdits {

    data class Edit(val offset: Int, val length: Int, val text: String)

    fun parse(lines: List<String>): List<Edit> = lines.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 4 || parts[0] != "edit") return@mapNotNull null
        val offset = parts[1].toIntOrNull() ?: return@mapNotNull null
        val length = parts[2].toIntOrNull() ?: return@mapNotNull null
        if (offset < 0 || length < 0) return@mapNotNull null
        Edit(offset, length, unescape(parts[3]))
    }

    /** Reverses the server's escaping: `\n`, `\r`, `\t` and `\\`. */
    fun unescape(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/** The formatting half of [SERVER_SOURCE]. No `$` anywhere in it: this is a Kotlin raw string. */
internal const val FORMATTER_SOURCE = """
static class Reformatter
{
    static Microsoft.CodeAnalysis.AdhocWorkspace workspace;

    public static int Run(string[] arguments, TextWriter output)
    {
        if (arguments.Length < 5) return 255;
        int start = int.Parse(arguments[0]);
        int end = int.Parse(arguments[1]);
        int indent = Math.Max(1, int.Parse(arguments[2]));
        bool tabs = arguments[3] == "1";
        Snapshot snapshot = Project.Open(arguments.Skip(5).ToArray(), Path.GetFullPath(arguments[4]));
        if (snapshot.Buffer == null) return 0;

        SyntaxNode root = snapshot.Buffer.GetRoot(CancellationToken.None);
        // Text that does not parse is left as it is: the formatter would move what it guessed at.
        if (root.GetDiagnostics().Any(d => d.Severity == DiagnosticSeverity.Error)) return 3;

        if (workspace == null) workspace = new Microsoft.CodeAnalysis.AdhocWorkspace();
        var options = workspace.Options
            .WithChangedOption(Microsoft.CodeAnalysis.Formatting.FormattingOptions.IndentationSize, LanguageNames.CSharp, indent)
            .WithChangedOption(Microsoft.CodeAnalysis.Formatting.FormattingOptions.TabSize, LanguageNames.CSharp, indent)
            .WithChangedOption(Microsoft.CodeAnalysis.Formatting.FormattingOptions.UseTabs, LanguageNames.CSharp, tabs);

        IList<Microsoft.CodeAnalysis.Text.TextChange> changes;
        if (start >= 0 && end > start)
        {
            int length = root.FullSpan.End;
            var span = Microsoft.CodeAnalysis.Text.TextSpan.FromBounds(Math.Min(start, length), Math.Min(end, length));
            changes = Microsoft.CodeAnalysis.Formatting.Formatter.GetFormattedTextChanges(root, span, workspace, options, CancellationToken.None);
        }
        else changes = Microsoft.CodeAnalysis.Formatting.Formatter.GetFormattedTextChanges(root, workspace, options, CancellationToken.None);

        foreach (var change in changes.OrderBy(c => c.Span.Start))
        {
            string text = (change.NewText ?? "").Replace("\\", "\\\\").Replace("\t", "\\t").Replace("\r", "\\r").Replace("\n", "\\n");
            output.WriteLine("edit\t" + change.Span.Start + "\t" + change.Span.Length + "\t" + text);
        }
        return 0;
    }
}
"""
