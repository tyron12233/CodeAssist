package dev.codeassist.csharp.sdk

import android.util.Log
import dev.codeassist.csharp.util.TAG
import dev.codeassist.csharp.util.ToolCancellation

/**
 * Reports every diagnostic of a compilation at once, syntax and semantic together, as an IDE does.
 *
 * Why not `csc`: it stops after the syntax stage,
 * so while any file has a syntax error it prints only those and nothing else.
 * The server ([CHECKER_SOURCE]) asks Roslyn for the diagnostics itself
 * (`CSharpCompilation.GetDiagnostics`), which carries on past syntax errors.
 *
 * It takes the same arguments `csc` would, and prints
 * them in `csc`'s format (`path(line,col): error CS0029: ...`), so `CsDiagnostics` reads either.
 * It does not emit an assembly: it is for the editor's check and for a failed build's problem list, never for producing a DLL.
 */
internal object CsChecker {

    /** What the program answers when it could not do its work at all (an exception, not a compile error). */
    private const val FAILED = 255

    /**
     * Check with arguments, as compile does. Returns 0 when there are no errors, 1 when there are,
     * and null when the program could not be used or failed inside (then onLine may already have been given its message).
     */
    fun check(
        sdk: DotnetSdk,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? {
        return ask(sdk, listOf("@@check") + arguments, cancellation, onLine)
    }

    /**
     * Check one file of the project: the buffer being edited, at [bufferPath] among the sources in [arguments].
     * The reply is that file's diagnostics, syntax and semantic, and the syntax errors of the other files.
     * It costs a fraction of [check] on a large project, because only the one file's code is bound, against a
     * compilation the server kept from the request before. Same return contract as [check].
     */
    fun checkFile(
        sdk: DotnetSdk,
        bufferPath: String,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? = ask(sdk, listOf("@@checkfile", bufferPath) + arguments, cancellation, onLine)

    private fun ask(
        sdk: DotnetSdk,
        request: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? {
        val exitCode = CsServer.compile(sdk, request, cancellation, onLine) ?: return null
        if (exitCode == FAILED) {
            Log.d(TAG, "check: the program failed inside; see the lines above")
            return null
        }
        return exitCode
    }
}

/**
 * The diagnostics half of [SERVER_SOURCE]. No `$` anywhere in it: this is a Kotlin raw string.
 */
internal const val CHECKER_SOURCE = """
static class Checker
{
    // Every diagnostic of the project, as `csc` would print them had it not stopped at the syntax stage.
    public static int Run(string[] arguments, TextWriter output)
    {
        Snapshot snapshot = Project.Open(arguments, null);
        int errors = Report(snapshot.Problems, output);
        if (snapshot.Compilation == null) return 1;
        CSharpCompilation compilation = snapshot.Compilation;

        var all = compilation.GetDiagnostics(CancellationToken.None).ToList();
        // The compilation's own list may leave out the hidden "unnecessary using" (CS8019); a file's semantic model
        // carries it. Only the first file is asked, the buffer being edited, and only when it has no errors: the
        // semantic model binds the file a second time.
        if (snapshot.First != null && !all.Any(d => d.Severity == DiagnosticSeverity.Error) && !all.Any(d => d.Id == "CS8019"))
            all.AddRange(compilation.GetSemanticModel(snapshot.First).GetDiagnostics(null, CancellationToken.None)
                .Where(d => d.Id == "CS8019"));
        return errors + Print(all, output) > 0 ? 1 : 0;
    }

    // The diagnostics of one file, with the syntax errors of the others. Binding stops at the file's own code, and
    // the other files' declarations it needs, so the cost follows the file and not the project.
    public static int RunFile(string bufferPath, string[] arguments, TextWriter output)
    {
        Snapshot snapshot = Project.Open(arguments, Path.GetFullPath(bufferPath));
        int errors = Report(snapshot.Problems, output);
        if (snapshot.Compilation == null) return 1;
        if (snapshot.Buffer == null) return Run(arguments, output);

        var all = snapshot.Compilation.GetSemanticModel(snapshot.Buffer).GetDiagnostics(null, CancellationToken.None).ToList();
        foreach (SyntaxTree tree in snapshot.Compilation.SyntaxTrees)
            if (tree != snapshot.Buffer) all.AddRange(tree.GetDiagnostics(CancellationToken.None));
        return errors + Print(all, output) > 0 ? 1 : 0;
    }

    static int Report(List<string> problems, TextWriter output)
    {
        int errors = 0;
        foreach (string problem in problems)
        {
            output.WriteLine(problem);
            if (problem.Contains("error ")) errors++;
        }
        return errors;
    }

    // Prints errors, warnings and the hidden unnecessary-using note in `csc`'s format. Returns the number of errors.
    static int Print(List<Diagnostic> all, TextWriter output)
    {
        int errors = 0;
        var found = all
            .Where(d => !d.IsSuppressed && (d.Severity == DiagnosticSeverity.Error || d.Severity == DiagnosticSeverity.Warning
                || (d.Severity == DiagnosticSeverity.Hidden && d.Id == "CS8019")))
            .OrderBy(d => d.Location.SourceTree == null ? "" : d.Location.SourceTree.FilePath, StringComparer.Ordinal)
            .ThenBy(d => d.Location.SourceSpan.Start);
        foreach (var d in found)
        {
            output.WriteLine(d.ToString());
            if (d.Severity == DiagnosticSeverity.Error) errors++;
        }
        return errors;
    }
}
"""
