package dev.codeassist.csharp.sdk

import android.util.Log
import dev.codeassist.csharp.util.TAG
import dev.codeassist.csharp.util.ToolCancellation

/**
 * Completion from Roslyn's semantic model, answered by the resident server ([CsServer]).
 *
 * Why Roslyn and not the symbol table: the table is only the public surface of DLLs, so it cannot know a local
 * variable, an internal class of the project, an inherited member, what a method returns, or an extension method.
 * The semantic model answers all of these with `LookupSymbols`, the call an IDE's completion is built on.
 *
 * The request is a response file like the compiler's: its first lines are the mode (`@@complete` or `@@signature`),
 * the caret offset, the offset where the typed word starts (unused by `@@signature`), and the buffer's path; the
 * rest are the compiler's arguments. The reply to `@@complete` is one `item` line per candidate (see
 * [CsCompletionItems.parse]); to `@@signature` a `sighelp` line and one `sig` line per overload (see
 * [CsSignatureLines.parse]); then `@@CS-DONE@@ <code>`.
 */
internal object CsCompleter {

    private const val FAILED = 255

    /** Starts the server ahead of the first request; see [ResidentProgram.warm]. */
    fun warm(sdk: DotnetSdk) = CsServer.warm(sdk)

    /**
     * The candidates at caret in the buffer at bufferPath, whose word starts at wordStart,
     * given the compiler arguments of the project with that buffer among the sources. Null when the program could not be
     * used; the lines it printed otherwise go to onLine.
     */
    fun complete(
        sdk: DotnetSdk,
        bufferPath: String,
        caret: Int,
        wordStart: Int,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? {
        return ask(sdk, "@@complete", bufferPath, caret, wordStart, arguments, cancellation, onLine)
    }

    /**
     * The signatures of the call whose argument list holds caret in the buffer at bufferPath.
     * Same contract as complete: null when the program could not be used, else its exit code, with the lines going to [onLine]
     * (none when the caret is in no call).
     */
    fun signatures(
        sdk: DotnetSdk,
        bufferPath: String,
        caret: Int,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? = ask(sdk, "@@signature", bufferPath, caret, caret, arguments, cancellation, onLine)

    private fun ask(
        sdk: DotnetSdk,
        mode: String,
        bufferPath: String,
        caret: Int,
        wordStart: Int,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? {
        val request = listOf(mode, caret.toString(), wordStart.toString(), bufferPath) + arguments
        val exitCode = CsServer.compile(sdk, request, cancellation, onLine) ?: return null
        if (exitCode == FAILED) {
            Log.d(TAG, "$mode: the program failed inside; see the lines above")
            return null
        }
        return exitCode
    }
}

/**
 * The completion half of [SERVER_SOURCE]. No `$` anywhere in it: this is a Kotlin raw string; and no triple
 * quote. The compilation it asks comes from the server's `Project`, which keeps it between requests.
 */
internal const val COMPLETER_SOURCE = """
static class Completer
{
    const int Limit = 400;

    public static int Run(string[] arguments, TextWriter output)
    {
        if (arguments.Length < 4 || (arguments[0] != "@@complete" && arguments[0] != "@@signature")) return 255;
        bool signature = arguments[0] == "@@signature";
        int caret = int.Parse(arguments[1]);
        int wordStart = int.Parse(arguments[2]);
        string bufferPath = Path.GetFullPath(arguments[3]);
        string[] rest = arguments.Skip(4).ToArray();

        Snapshot snapshot = Project.Open(rest, bufferPath);
        SyntaxTree buffer = snapshot.Buffer;
        if (buffer == null) return 0;
        CSharpCompilation compilation = snapshot.Compilation;
        SemanticModel model = compilation.GetSemanticModel(buffer, false);
        string source = buffer.GetText(CancellationToken.None).ToString();
        caret = Math.Max(0, Math.Min(caret, source.Length));
        wordStart = Math.Max(0, Math.Min(wordStart, caret));
        if (signature) return Signature(model, buffer, source, caret, output);
        string prefix = source.Substring(wordStart, caret - wordStart);

        IEnumerable<ISymbol> symbols = Candidates(model, buffer, source, wordStart, caret);
        if (symbols == null) return 0;

        var groups = new Dictionary<string, List<ISymbol>>();
        var order = new List<string>();
        foreach (ISymbol symbol in symbols)
        {
            if (!Wanted(symbol)) continue;
            string name = symbol.Name;
            if (name.Length == 0 || !Matches(name, prefix)) continue;
            string key = Category(symbol) + "\t" + name;
            List<ISymbol> list;
            if (!groups.TryGetValue(key, out list)) { list = new List<ISymbol>(); groups[key] = list; order.Add(key); }
            list.Add(symbol);
        }

        int count = 0;
        foreach (string key in order)
        {
            if (count++ >= Limit) break;
            List<ISymbol> list = groups[key];
            ISymbol first = list[0];
            string kind = Category(first);
            string detail = Detail(model, wordStart, first);
            bool takesArguments = false;
            if (first is IMethodSymbol)
                takesArguments = list.All(s => s is IMethodSymbol && ((IMethodSymbol)s).Parameters.Length > 0);
            string container = Container(first);
            bool deprecated = first.GetAttributes().Any(
                a => a.AttributeClass != null && a.AttributeClass.Name == "ObsoleteAttribute");
            output.WriteLine("item\t" + Clean(first.Name) + "\t" + kind + "\t" + Clean(detail)
                + "\t" + (takesArguments ? "1" : "0") + "\t" + Clean(container) + "\t" + (deprecated ? "1" : "0")
                + "\t" + (list.Count > 1 ? list.Count.ToString() : "1")
                + "\t" + Documentation(first, false));
        }
        return 0;
    }

    // The documentation of a DLL, read from the .xml file beside it. Roslyn's own XmlDocumentationProvider is in the
    // Workspaces assembly, which is not shipped, so this is the small part of it that is needed: member id to the
    // member's element. The file is read on the first question and each element cut out only when asked for.
    internal sealed class XmlDocs : DocumentationProvider
    {
        static readonly Regex Member = new Regex("<member name=\"([^\"]+)\"[^>]*>.*?</member>", RegexOptions.Singleline);
        readonly string path;
        string text;
        Dictionary<string, KeyValuePair<int, int>> members;

        public XmlDocs(string path) { this.path = path; }

        protected override string GetDocumentationForSymbol(
            string documentationMemberID, System.Globalization.CultureInfo preferredCulture,
            CancellationToken cancellationToken = default(CancellationToken))
        {
            lock (this)
            {
                if (members == null)
                {
                    members = new Dictionary<string, KeyValuePair<int, int>>();
                    try
                    {
                        text = File.ReadAllText(path);
                        foreach (Match m in Member.Matches(text))
                            members[m.Groups[1].Value] = new KeyValuePair<int, int>(m.Index, m.Length);
                    }
                    catch (Exception) { text = ""; }
                }
                KeyValuePair<int, int> at;
                return members.TryGetValue(documentationMemberID, out at) ? text.Substring(at.Key, at.Value) : "";
            }
        }

        public override bool Equals(object obj) { XmlDocs other = obj as XmlDocs; return other != null && other.path == path; }
        public override int GetHashCode() { return path.GetHashCode(); }
    }

    static readonly Regex SummaryElement = new Regex("<summary>.*?</summary>", RegexOptions.Singleline);
    static readonly Regex ParamElement = new Regex("<param name=\"[^\"]*\">.*?</param>", RegexOptions.Singleline);

    // The summary of the symbol's XML documentation, and with [withParams] its param elements, escaped onto one line
    // (a backslash, a tab and a newline become \\, \t and \n). Empty when it has none. Only those elements are sent:
    // remarks and examples can be long and the popup does not show them.
    static string Documentation(ISymbol symbol, bool withParams)
    {
        try
        {
            ISymbol source = symbol.OriginalDefinition;
            IMethodSymbol method = source as IMethodSymbol;
            if (method != null && method.ReducedFrom != null) source = method.ReducedFrom.OriginalDefinition;
            string xml = source.GetDocumentationCommentXml(null, false, CancellationToken.None);
            if (string.IsNullOrEmpty(xml)) return "";
            var slim = new StringBuilder();
            Match summary = SummaryElement.Match(xml);
            if (summary.Success) slim.Append(summary.Value);
            if (withParams)
                foreach (Match p in ParamElement.Matches(xml)) slim.Append(p.Value);
            if (xml.Contains("<inheritdoc")) slim.Append("<inheritdoc/>");
            return slim.ToString().Replace("\\", "\\\\").Replace("\t", "\\t").Replace("\r", "").Replace("\n", "\\n");
        }
        catch (Exception) { return ""; }
    }

    const int SignatureLimit = 30;

    // The overloads of the call whose argument list holds the caret. Prints nothing when the caret is in no call.
    //   sighelp <TAB> active signature <TAB> active parameter
    //   sig <TAB> label <TAB> start,end;start,end   (the range of each parameter inside the label)
    static int Signature(SemanticModel model, SyntaxTree tree, string source, int caret, TextWriter output)
    {
        SyntaxNode root = tree.GetRoot(CancellationToken.None);
        SyntaxToken token = root.FindToken(Math.Max(0, caret - 1));
        ArgumentListSyntax list = null;
        for (SyntaxNode node = token.Parent; node != null; node = node.Parent)
        {
            ArgumentListSyntax candidate = node as ArgumentListSyntax;
            if (candidate == null) continue;
            if (candidate.OpenParenToken.Span.End > caret) continue;
            if (!candidate.CloseParenToken.IsMissing && caret > candidate.CloseParenToken.SpanStart) continue;
            list = candidate;
            break;
        }
        if (list == null) return 0;

        // The argument the caret is in: the commas before it.
        int active = list.Arguments.GetSeparators().Count(c => c.Span.End <= caret);

        SyntaxNode owner = list.Parent;
        var methods = new List<IMethodSymbol>();
        SymbolInfo info = default(SymbolInfo);
        InvocationExpressionSyntax invocation = owner as InvocationExpressionSyntax;
        BaseObjectCreationExpressionSyntax creation = owner as BaseObjectCreationExpressionSyntax;
        ConstructorInitializerSyntax initializer = owner as ConstructorInitializerSyntax;
        if (invocation != null)
        {
            info = model.GetSymbolInfo(invocation, CancellationToken.None);
            foreach (ISymbol s in model.GetMemberGroup(invocation.Expression, CancellationToken.None))
            {
                IMethodSymbol m = s as IMethodSymbol;
                if (m != null) methods.Add(m);
            }
            if (methods.Count == 0)
            {
                IMethodSymbol one = info.Symbol as IMethodSymbol;
                if (one != null) methods.Add(one);
                foreach (ISymbol s in info.CandidateSymbols)
                {
                    IMethodSymbol m = s as IMethodSymbol;
                    if (m != null) methods.Add(m);
                }
            }
        }
        else if (creation != null || initializer != null)
        {
            INamedTypeSymbol type = null;
            if (creation != null)
            {
                info = model.GetSymbolInfo(creation, CancellationToken.None);
                type = model.GetTypeInfo(creation, CancellationToken.None).Type as INamedTypeSymbol;
            }
            else
            {
                info = model.GetSymbolInfo(initializer, CancellationToken.None);
                ConstructorDeclarationSyntax declaration = initializer.Parent as ConstructorDeclarationSyntax;
                IMethodSymbol declared = declaration == null
                    ? null : model.GetDeclaredSymbol(declaration, CancellationToken.None) as IMethodSymbol;
                if (declared != null)
                    type = initializer.ThisOrBaseKeyword.IsKind(SyntaxKind.ThisKeyword)
                        ? declared.ContainingType : declared.ContainingType.BaseType;
            }
            if (type == null || type.TypeKind == TypeKind.Error) return 0;
            foreach (IMethodSymbol m in type.InstanceConstructors)
                if (model.IsAccessible(caret, m)) methods.Add(m);
        }
        else return 0;

        if (methods.Count == 0) return 0;
        methods = methods.OrderBy(m => m.Parameters.Length).ThenBy(m => m.ToDisplayString())
            .Take(SignatureLimit).ToList();

        ISymbol chosen = info.Symbol;
        int activeSignature = -1;
        if (chosen != null)
            for (int i = 0; i < methods.Count && activeSignature < 0; i++)
                if (SymbolEqualityComparer.Default.Equals(methods[i], chosen)
                    || SymbolEqualityComparer.Default.Equals(methods[i].OriginalDefinition, chosen.OriginalDefinition))
                    activeSignature = i;
        // An unfinished call has no single binding: the first overload that has room for the argument.
        if (activeSignature < 0)
            for (int i = 0; i < methods.Count && activeSignature < 0; i++)
                if (methods[i].Parameters.Length > active
                    || (methods[i].Parameters.Length > 0
                        && methods[i].Parameters[methods[i].Parameters.Length - 1].IsParams))
                    activeSignature = i;
        if (activeSignature < 0) activeSignature = 0;

        output.WriteLine("sighelp\t" + activeSignature + "\t" + active);
        foreach (IMethodSymbol method in methods)
        {
            var label = new StringBuilder();
            label.Append(method.MethodKind == MethodKind.Constructor ? method.ContainingType.Name : method.Name);
            if (method.TypeParameters.Length > 0)
                label.Append('<').Append(string.Join(", ", method.TypeParameters.Select(t => t.Name))).Append('>');
            label.Append('(');
            var ranges = new List<string>();
            for (int i = 0; i < method.Parameters.Length; i++)
            {
                if (i > 0) label.Append(", ");
                int start = label.Length;
                label.Append(ParameterText(model, caret, method.Parameters[i]));
                ranges.Add(start + "," + label.Length);
            }
            label.Append(')');
            output.WriteLine("sig\t" + Clean(label.ToString()) + "\t" + string.Join(";", ranges)
                + "\t" + Documentation(method, true) + "\t" + string.Join(";", method.Parameters.Select(p => p.Name)));
        }
        return 0;
    }

    static string ParameterText(SemanticModel model, int position, IParameterSymbol parameter)
    {
        var text = new StringBuilder();
        if (parameter.IsParams) text.Append("params ");
        if (parameter.RefKind == RefKind.Out) text.Append("out ");
        else if (parameter.RefKind == RefKind.Ref) text.Append("ref ");
        else if (parameter.RefKind == RefKind.In) text.Append("in ");
        string type;
        try { type = parameter.Type.ToMinimalDisplayString(model, position); }
        catch (Exception) { type = parameter.Type.Name; }
        text.Append(type).Append(' ').Append(parameter.Name);
        if (parameter.HasExplicitDefaultValue)
        {
            object value = parameter.ExplicitDefaultValue;
            string shown = value == null ? "default"
                : value is string ? "\"" + value + "\""
                : value is bool ? ((bool)value ? "true" : "false")
                : Convert.ToString(value, System.Globalization.CultureInfo.InvariantCulture);
            text.Append(" = ").Append(shown);
        }
        return text.ToString();
    }

    // The symbols that may be written at the caret, or null when the caret is somewhere nothing is offered.
    static IEnumerable<ISymbol> Candidates(
        SemanticModel model, SyntaxTree tree, string source, int wordStart, int caret)
    {
        SyntaxNode root = tree.GetRoot(CancellationToken.None);
        int dot = wordStart - 1;
        while (dot >= 0 && char.IsWhiteSpace(source[dot])) dot--;
        bool afterDot = dot >= 0 && source[dot] == '.';
        int position = caret;

        if (afterDot)
        {
            SyntaxToken token = root.FindToken(dot);
            ExpressionSyntax receiver = ReceiverOf(token);
            if (receiver == null) return null;

            SymbolInfo info = model.GetSymbolInfo(receiver, CancellationToken.None);
            ISymbol symbol = info.Symbol;
            if (symbol is INamespaceSymbol)
                return model.LookupNamespacesAndTypes(position, (INamespaceSymbol)symbol, null);
            if (symbol is ITypeSymbol && !(symbol is ITypeParameterSymbol))
            {
                // A type's own name: its static members and nested types. (`Color Color` where a property has the
                // type's name is read as the type; that case is not handled.)
                return model.LookupStaticMembers(position, (ITypeSymbol)symbol, null);
            }
            ITypeSymbol type = model.GetTypeInfo(receiver, CancellationToken.None).Type;
            if (type == null || type.TypeKind == TypeKind.Error) return null;
            // A nullable value type completes as the type inside it, as the language reads `x.` on an int?.
            INamedTypeSymbol named = type as INamedTypeSymbol;
            if (named != null && named.IsValueType
                && named.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T)
                type = named.TypeArguments[0];
            return model.LookupSymbols(position, type, null, true)
                .Where(s => !s.IsStatic || s.Kind == SymbolKind.NamedType);
        }

        // After `new` only types make sense.
        SyntaxToken before = root.FindToken(Math.Max(0, wordStart - 1));
        if (before.Span.End <= wordStart && before.IsKind(SyntaxKind.NewKeyword))
            return model.LookupNamespacesAndTypes(position, null, null);

        return model.LookupSymbols(position, null, null, false);
    }

    static ExpressionSyntax ReceiverOf(SyntaxToken dot)
    {
        SyntaxNode parent = dot.Parent;
        MemberAccessExpressionSyntax access = parent as MemberAccessExpressionSyntax;
        if (access != null) return access.Expression;
        QualifiedNameSyntax qualified = parent as QualifiedNameSyntax;
        if (qualified != null) return qualified.Left;
        // `x?.` : the dot is the `.` of a member binding, whose receiver is the expression of the conditional access.
        MemberBindingExpressionSyntax binding = parent as MemberBindingExpressionSyntax;
        if (binding != null)
        {
            ConditionalAccessExpressionSyntax conditional = binding.Ancestors()
                .OfType<ConditionalAccessExpressionSyntax>().FirstOrDefault();
            if (conditional != null) return conditional.Expression;
        }
        return null;
    }

    // What a name may be, without what the language hides: constructors, accessors, operators, compiler names.
    static bool Wanted(ISymbol symbol)
    {
        if (symbol.IsImplicitlyDeclared) return false;
        if (symbol.Name.IndexOfAny(new char[] { '<', '>', '.', (char)36 }) >= 0) return false;
        IMethodSymbol method = symbol as IMethodSymbol;
        if (method != null)
            return method.MethodKind == MethodKind.Ordinary || method.MethodKind == MethodKind.ReducedExtension
                || method.MethodKind == MethodKind.LocalFunction;
        return symbol.Kind != SymbolKind.Label;
    }

    // A subsequence match, case-insensitive: lenient on purpose, the editor applies its own matching after.
    static bool Matches(string name, string prefix)
    {
        if (prefix.Length == 0) return true;
        int at = 0;
        foreach (char c in prefix)
        {
            at = name.IndexOf(c.ToString(), at, StringComparison.OrdinalIgnoreCase);
            if (at < 0) return false;
            at++;
        }
        return true;
    }

    static string Category(ISymbol symbol)
    {
        switch (symbol.Kind)
        {
            case SymbolKind.Method:
                IMethodSymbol method = (IMethodSymbol)symbol;
                if (method.MethodKind == MethodKind.ReducedExtension) return "extension";
                return method.MethodKind == MethodKind.LocalFunction ? "local" : "method";
            case SymbolKind.Property: return "property";
            case SymbolKind.Field:
                IFieldSymbol field = (IFieldSymbol)symbol;
                if (field.ContainingType != null && field.ContainingType.TypeKind == TypeKind.Enum) return "enummember";
                return field.IsConst ? "constant" : "field";
            case SymbolKind.Event: return "event";
            case SymbolKind.Local: return "local";
            case SymbolKind.Parameter: return "parameter";
            case SymbolKind.Namespace: return "namespace";
            case SymbolKind.TypeParameter: return "typeparameter";
            case SymbolKind.NamedType:
                switch (((INamedTypeSymbol)symbol).TypeKind)
                {
                    case TypeKind.Interface: return "interface";
                    case TypeKind.Enum: return "enum";
                    case TypeKind.Struct: return "struct";
                    case TypeKind.Delegate: return "delegate";
                    default: return "class";
                }
            default: return "other";
        }
    }

    // The type of a value, or the return type of a method, as it would be written at the caret.
    static string Detail(SemanticModel model, int position, ISymbol symbol)
    {
        ITypeSymbol type = null;
        IMethodSymbol method = symbol as IMethodSymbol;
        if (method != null) type = method.ReturnType;
        else if (symbol is IPropertySymbol) type = ((IPropertySymbol)symbol).Type;
        else if (symbol is IFieldSymbol) type = ((IFieldSymbol)symbol).Type;
        else if (symbol is ILocalSymbol) type = ((ILocalSymbol)symbol).Type;
        else if (symbol is IParameterSymbol) type = ((IParameterSymbol)symbol).Type;
        else if (symbol is IEventSymbol) type = ((IEventSymbol)symbol).Type;
        if (type == null) return "";
        try { return type.ToMinimalDisplayString(model, position); }
        catch (Exception) { return type.Name; }
    }

    // The declaring type, or the namespace for a type; empty for a local or a parameter.
    static string Container(ISymbol symbol)
    {
        if (symbol is ILocalSymbol || symbol is IParameterSymbol) return "";
        if (symbol.ContainingType != null) return symbol.ContainingType.Name;
        INamespaceSymbol ns = symbol.ContainingNamespace;
        return ns != null && !ns.IsGlobalNamespace ? ns.ToDisplayString() : "";
    }

    static string Clean(string text)
    {
        return text == null ? "" : text.Replace('\t', ' ').Replace('\r', ' ').Replace('\n', ' ');
    }
}
"""
