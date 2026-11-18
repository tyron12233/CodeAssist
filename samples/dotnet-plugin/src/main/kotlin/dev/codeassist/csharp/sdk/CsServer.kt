package dev.codeassist.csharp.sdk

/**
 * The one .NET process this plugin keeps running. It compiles ([CsCompilerHost]), reports diagnostics
 * ([CsChecker]), answers completion and parameter help ([CsCompleter]), and resolves and colours the names in
 * a file ([CsNavigator]).
 *
 * One process and not three: each would load the runtime and Roslyn for itself, well over a hundred megabytes
 * apiece, and each would parse the project and read the reference assemblies again. Here the editor's check and
 * completion work on the same compilation, kept from one request to the next and changed only where a file
 * changed. The cost is that requests wait for one another: completion asked during a build is answered after it.
 *
 * A request is a response file whose first line names the work (`@@compile`, `@@check`, `@@checkfile`,
 * `@@complete`, `@@signature`, `@@definition`, `@@hover`, `@@tokens`); see [ResidentProgram] for the protocol around it. The process ends itself
 * after a few idle minutes and starts again on the next request.
 */
internal object CsServer : ResidentProgram(
    label = "server",
    fileName = "CsServer",
    folder = "server",
    source = SERVER_SOURCE,
    // Roslyn is a compile-time reference for the checker and the completer; `csc` itself is reached by reflection.
    extraReferences = { sdk ->
        listOf(
            sdk.csc.parent.resolve("Microsoft.CodeAnalysis.dll"),
            sdk.csc.parent.resolve("Microsoft.CodeAnalysis.CSharp.dll"),
        )
    },
)

/**
 * The server's entry point and the project state its halves share. No `$` anywhere in it: this is a Kotlin
 * raw string. Roslyn is named only in classes reached through `Dispatch`: `Main` registers the resolver for
 * Roslyn's assemblies first, and the runtime loads them when `Dispatch` is first compiled, not before.
 */
private const val SERVER_MAIN = """using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.Loader;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;

static class CsServer
{
    static string roslyn;

    static int Main(string[] argv)
    {
        roslyn = argv[0];
        AssemblyLoadContext.Default.Resolving += (context, name) =>
        {
            string candidate = Path.Combine(roslyn, name.Name + ".dll");
            return File.Exists(candidate) ? context.LoadFromAssemblyPath(candidate) : null;
        };

        var input = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));
        var output = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false));
        output.AutoFlush = true;

        // Warm up, so that the first real request does not pay for loading Roslyn. Its result is not used.
        try { Dispatch(new string[] { "@@complete", "0", "0", "x.cs", "-nologo" }, TextWriter.Null); }
        catch (Exception) { }
        output.WriteLine("@@CS-READY@@");

        string line;
        while ((line = input.ReadLine()) != null)
        {
            if (line.Length == 0) continue;
            var capture = new StringWriter();
            int code = 255;
            try { code = Dispatch(File.ReadAllLines(line), capture); }
            catch (Exception e) { capture.WriteLine("server: " + (e.InnerException ?? e)); }
            string text = capture.ToString();
            output.Write(text);
            if (text.Length > 0 && !text.EndsWith("\n")) output.WriteLine();
            output.WriteLine("@@CS-DONE@@ " + code);
        }
        return 0;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    static int Dispatch(string[] arguments, TextWriter output)
    {
        if (arguments.Length == 0) return 255;
        switch (arguments[0])
        {
            case "@@complete":
            case "@@signature":
                return Completer.Run(arguments, output);
            case "@@check":
                return Checker.Run(arguments.Skip(1).ToArray(), output);
            case "@@checkfile":
                if (arguments.Length < 2) return 255;
                return Checker.RunFile(arguments[1], arguments.Skip(2).ToArray(), output);
            case "@@definition":
                return Navigator.Definition(arguments.Skip(1).ToArray(), output);
            case "@@hover":
                return Navigator.Hover(arguments.Skip(1).ToArray(), output);
            case "@@tokens":
                return Navigator.Tokens(arguments.Skip(1).ToArray(), output);
            case "@@compile":
                return Compiler.Run(roslyn, arguments.Skip(1).ToArray(), output);
            default:
                return 255;
        }
    }
}

// `csc` itself, loaded on the first build and called through its own `Main`, so a build is what the command
// line would have produced.
static class Compiler
{
    static MethodInfo main;
    static string loadError;

    public static int Run(string roslyn, string[] arguments, TextWriter output)
    {
        if (main == null && loadError == null)
        {
            try
            {
                Assembly csc = Assembly.LoadFrom(Path.Combine(roslyn, "csc.dll"));
                Type program = csc.GetType("Microsoft.CodeAnalysis.CSharp.CommandLine.Program");
                main = program.GetMethod("Main",
                    BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic, null, new Type[] { typeof(string[]) }, null);
            }
            catch (Exception e) { loadError = e.ToString(); }
        }
        if (main == null)
        {
            output.WriteLine("server: csc could not be loaded: " + loadError);
            return 255;
        }

        TextWriter oldOut = Console.Out;
        TextWriter oldErr = Console.Error;
        Console.SetOut(output);
        Console.SetError(output);
        try { return (int)main.Invoke(null, new object[] { arguments }); }
        finally
        {
            Console.SetOut(oldOut);
            Console.SetError(oldErr);
            // A build leaves a whole compilation behind; it is not needed again, and this process stays.
            GC.Collect();
        }
    }
}

// What a request works on: the compilation for its arguments, and the tree of the file it is about.
sealed class Snapshot
{
    public CSharpCompilation Compilation;
    public SyntaxTree Buffer;
    public SyntaxTree First;
    public List<string> Problems = new List<string>();
}

// The projects recent requests were about. A request's arguments are turned into a compilation by changing
// the one kept for that project: a file whose time and size are as before keeps its syntax tree, and the
// compilation keeps what it knows of the reference assemblies and of the unchanged files' declarations.
//
// A project that references another names it with `-projectref:<response file>`, a file holding that project's
// own arguments. It is opened the same way and handed over as a reference to its compilation, not to a DLL,
// so the referencing project sees its sources as they are on disk, built or not.
static class Project
{
    const int Kept = 8;

    // The metadata of a reference is read once and kept, with the time the file had.
    static readonly Dictionary<string, MetadataReference> References = new Dictionary<string, MetadataReference>();
    static readonly Dictionary<string, DateTime> Stamps = new Dictionary<string, DateTime>();

    static readonly Dictionary<string, ProjectState> States = new Dictionary<string, ProjectState>();
    static long clock;

    // `bufferPath` is the full path of the file the request is about, or null.
    public static Snapshot Open(string[] arguments, string bufferPath)
    {
        string key = "";
        foreach (string argument in arguments)
            if (argument.StartsWith("-projectkey:", StringComparison.Ordinal)) key = argument.Substring(12);
        var opening = new Dictionary<string, MetadataReference>(StringComparer.Ordinal);
        Snapshot snapshot = State("main:" + key).Open(arguments, bufferPath, opening);
        Forget();
        return snapshot;
    }

    static ProjectState State(string key)
    {
        ProjectState state;
        if (!States.TryGetValue(key, out state)) { state = new ProjectState(); States[key] = state; }
        state.Used = ++clock;
        return state;
    }

    static void Forget()
    {
        while (States.Count > Kept)
        {
            string oldest = States.OrderBy(pair => pair.Value.Used).First().Key;
            States.Remove(oldest);
        }
    }

    // The project whose arguments are in `responseFile`, as a reference. `opening` holds what this request has
    // opened already, and null for one still being opened: a project that references itself is left out.
    internal static MetadataReference Referenced(string responseFile, Dictionary<string, MetadataReference> opening, List<string> problems)
    {
        string path = Path.GetFullPath(responseFile);
        MetadataReference done;
        if (opening.TryGetValue(path, out done)) return done;
        opening[path] = null;
        string[] arguments;
        try { arguments = File.ReadAllLines(path); }
        catch (Exception)
        {
            problems.Add("error CS0006: Metadata file '" + responseFile + "' could not be found");
            return null;
        }
        ProjectState state = State("ref:" + path);
        Snapshot snapshot = state.Open(arguments, null, opening);
        if (snapshot.Compilation == null) return null;
        MetadataReference reference = state.AsReference();
        opening[path] = reference;
        return reference;
    }

    internal static MetadataReference Load(string path)
    {
        try
        {
            DateTime stamp = File.GetLastWriteTimeUtc(path);
            MetadataReference cached;
            DateTime cachedStamp;
            if (References.TryGetValue(path, out cached) && Stamps.TryGetValue(path, out cachedStamp)
                && cachedStamp == stamp)
                return cached;
            // The XML documentation beside the DLL, when there is one, is what `GetDocumentationCommentXml` reads.
            DocumentationProvider documentation = null;
            string xml = Path.ChangeExtension(path, ".xml");
            if (File.Exists(xml))
            {
                try { documentation = new Completer.XmlDocs(xml); }
                catch (Exception) { documentation = null; }
            }
            MetadataReference made = MetadataReference.CreateFromFile(
                path, default(MetadataReferenceProperties), documentation);
            References[path] = made;
            Stamps[path] = stamp;
            return made;
        }
        catch (Exception) { return null; }
    }
}

// One project as the last request about it left it.
sealed class ProjectState
{
    sealed class Parsed
    {
        public DateTime Stamp;
        public long Length;
        public SyntaxTree Tree;
    }

    public long Used;

    readonly Dictionary<string, Parsed> trees = new Dictionary<string, Parsed>();
    CSharpParseOptions parseOptions;
    CSharpCompilationOptions options;
    List<MetadataReference> references;
    CSharpCompilation compilation;
    CSharpCompilation referenceOf;
    MetadataReference reference;

    // This project as another's reference. The same object for as long as the compilation is the same one,
    // so that a project referencing it can tell nothing changed.
    public MetadataReference AsReference()
    {
        if (reference == null || !ReferenceEquals(referenceOf, compilation))
        {
            referenceOf = compilation;
            reference = compilation.ToMetadataReference();
        }
        return reference;
    }

    public Snapshot Open(string[] arguments, string bufferPath, Dictionary<string, MetadataReference> opening)
    {
        var snapshot = new Snapshot();
        var projects = new List<string>();
        var plain = new List<string>();
        foreach (string argument in arguments)
        {
            if (argument.StartsWith("-projectref:", StringComparison.Ordinal)) projects.Add(argument.Substring(12));
            else if (!argument.StartsWith("-projectkey:", StringComparison.Ordinal)) plain.Add(argument);
        }

        string baseDirectory = Directory.GetCurrentDirectory();
        string sdkDirectory = System.Runtime.InteropServices.RuntimeEnvironment.GetRuntimeDirectory();
        var parsed = CSharpCommandLineParser.Default.Parse(plain, baseDirectory, sdkDirectory, null);

        bool stop = false;
        foreach (var d in parsed.Errors)
        {
            snapshot.Problems.Add(d.ToString());
            if (d.Severity == DiagnosticSeverity.Error) stop = true;
        }
        if (stop) return snapshot;

        // The command line parser turns doc comments off unless /doc is given. They are wanted: the popup shows
        // them, and a using only a doc comment's cref names counts as used.
        CSharpParseOptions wanted = parsed.ParseOptions.WithDocumentationMode(DocumentationMode.Parse);
        if (parseOptions == null || !wanted.Equals(parseOptions))
        {
            trees.Clear();
            parseOptions = wanted;
        }

        var wantedTrees = new List<SyntaxTree>();
        var present = new HashSet<string>(StringComparer.Ordinal);
        foreach (var file in parsed.SourceFiles)
        {
            string path = Path.GetFullPath(file.Path);
            SyntaxTree tree = Tree(path);
            if (tree == null)
            {
                snapshot.Problems.Add("error CS2001: Source file '" + file.Path + "' could not be found");
                continue;
            }
            if (!present.Add(path)) continue;
            wantedTrees.Add(tree);
            if (snapshot.First == null) snapshot.First = tree;
            if (bufferPath != null && string.Equals(path, bufferPath, StringComparison.Ordinal)) snapshot.Buffer = tree;
        }
        foreach (string gone in trees.Keys.Where(k => !present.Contains(k)).ToList()) trees.Remove(gone);

        var wantedReferences = new List<MetadataReference>();
        foreach (var reference in parsed.MetadataReferences)
        {
            MetadataReference loaded = Project.Load(reference.Reference);
            if (loaded == null)
                snapshot.Problems.Add("error CS0006: Metadata file '" + reference.Reference + "' could not be found");
            else wantedReferences.Add(loaded);
        }
        foreach (string project in projects)
        {
            MetadataReference loaded = Project.Referenced(project, opening, snapshot.Problems);
            if (loaded != null) wantedReferences.Add(loaded);
        }

        if (compilation != null && parsed.CompilationOptions.Equals(options))
        {
            // A referenced project that changed is a new reference; the files of this one are still as parsed.
            if (!references.SequenceEqual(wantedReferences))
            {
                compilation = compilation.WithReferences(wantedReferences);
                references = wantedReferences;
            }
            var wantedSet = new HashSet<SyntaxTree>(wantedTrees);
            var kept = new HashSet<SyntaxTree>(compilation.SyntaxTrees);
            var removed = kept.Where(t => !wantedSet.Contains(t)).ToList();
            var added = wantedTrees.Where(t => !kept.Contains(t)).ToList();
            if (removed.Count > 0) compilation = compilation.RemoveSyntaxTrees(removed);
            if (added.Count > 0) compilation = compilation.AddSyntaxTrees(added);
        }
        else
        {
            options = parsed.CompilationOptions;
            references = wantedReferences;
            // The name is the assembly's: a project that references this one knows it by that.
            compilation = CSharpCompilation.Create(parsed.CompilationName ?? "project", wantedTrees, wantedReferences, options);
        }
        snapshot.Compilation = compilation;
        return snapshot;
    }

    SyntaxTree Tree(string path)
    {
        try
        {
            var info = new FileInfo(path);
            if (!info.Exists) { trees.Remove(path); return null; }
            Parsed known;
            if (trees.TryGetValue(path, out known) && known.Stamp == info.LastWriteTimeUtc && known.Length == info.Length)
                return known.Tree;
            SyntaxTree tree = CSharpSyntaxTree.ParseText(File.ReadAllText(path), parseOptions, path, null, CancellationToken.None);
            trees[path] = new Parsed { Stamp = info.LastWriteTimeUtc, Length = info.Length, Tree = tree };
            return tree;
        }
        catch (Exception) { return null; }
    }
}
"""

/** The whole program: the entry point and shared state, then the checker, the completer and the navigator. */
internal const val SERVER_SOURCE = SERVER_MAIN + CHECKER_SOURCE + COMPLETER_SOURCE + NAVIGATOR_SOURCE
