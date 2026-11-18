package dev.codeassist.csharp.sdk

import android.util.Log
import dev.codeassist.csharp.util.TAG
import dev.codeassist.csharp.util.ToolCancellation

/**
 * What the editor asks about a symbol in the buffer, answered by the resident server ([CsServer]) from the
 * same compilation the check and completion use: where it is declared, what it is, and how each name in the
 * file should be coloured.
 *
 * Requests follow [CsCompleter]'s shape: the mode, the caret (not for `@@tokens`), the buffer's path, then the
 * compiler's arguments. The replies are tab-separated lines:
 *
 * - `@@definition`: `def <path> <offset> <label>`, one per source location of the symbol. A symbol that
 *   comes from a reference assembly has no source and gives no lines.
 * - `@@hover`: one `hover <kind> <name> <container> <signature> <doc>` line, or none.
 * - `@@tokens`: `tok <start> <length> <kind> <modifiers>`, one per name, in document order.
 */
internal object CsNavigator {

    private const val FAILED = 255

    fun definition(
        sdk: DotnetSdk,
        bufferPath: String,
        caret: Int,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? = ask(sdk, listOf("@@definition", caret.toString(), bufferPath) + arguments, cancellation, onLine)

    fun hover(
        sdk: DotnetSdk,
        bufferPath: String,
        caret: Int,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? = ask(sdk, listOf("@@hover", caret.toString(), bufferPath) + arguments, cancellation, onLine)

    fun tokens(
        sdk: DotnetSdk,
        bufferPath: String,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? = ask(sdk, listOf("@@tokens", bufferPath) + arguments, cancellation, onLine)

    private fun ask(
        sdk: DotnetSdk,
        request: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? {
        val exitCode = CsServer.compile(sdk, request, cancellation, onLine) ?: return null
        if (exitCode == FAILED) {
            Log.d(TAG, "${request.first()}: the program failed inside; see the lines above")
            return null
        }
        return exitCode
    }
}

/**
 * The navigation half of [SERVER_SOURCE]. No `$` anywhere in it: this is a Kotlin raw string.
 */
internal const val NAVIGATOR_SOURCE = """
static class Navigator
{
    static readonly SymbolDisplayFormat Short = SymbolDisplayFormat.MinimallyQualifiedFormat;

    public static int Definition(string[] arguments, TextWriter output)
    {
        if (arguments.Length < 2) return 255;
        Snapshot snapshot = Project.Open(arguments.Skip(2).ToArray(), Path.GetFullPath(arguments[1]));
        if (snapshot.Buffer == null) return 0;
        bool declared;
        ISymbol symbol = SymbolAt(snapshot, int.Parse(arguments[0]), out declared);
        if (symbol == null) return 0;
        symbol = Source(symbol);
        foreach (Location location in symbol.Locations)
        {
            if (!location.IsInSource || location.SourceTree == null) continue;
            output.WriteLine("def\t" + location.SourceTree.FilePath + "\t" + location.SourceSpan.Start + "\t"
                + Completer.Clean(symbol.ToDisplayString(Short)));
        }
        return 0;
    }

    public static int Hover(string[] arguments, TextWriter output)
    {
        if (arguments.Length < 2) return 255;
        Snapshot snapshot = Project.Open(arguments.Skip(2).ToArray(), Path.GetFullPath(arguments[1]));
        if (snapshot.Buffer == null) return 0;
        int caret = int.Parse(arguments[0]);
        bool declared;
        ISymbol symbol = SymbolAt(snapshot, caret, out declared);
        if (symbol == null) return 0;
        SemanticModel model = snapshot.Compilation.GetSemanticModel(snapshot.Buffer, false);
        int position = Math.Max(0, Math.Min(caret, snapshot.Buffer.Length));
        string signature = symbol.ToMinimalDisplayString(model, position, Short);
        ILocalSymbol local = symbol as ILocalSymbol;
        IParameterSymbol parameter = symbol as IParameterSymbol;
        if (local != null) signature = local.Type.ToMinimalDisplayString(model, position, Short) + " " + local.Name;
        if (parameter != null) signature = parameter.Type.ToMinimalDisplayString(model, position, Short) + " " + parameter.Name;
        output.WriteLine("hover\t" + Kind(symbol) + "\t" + Completer.Clean(symbol.Name) + "\t"
            + Completer.Clean(Completer.Container(symbol)) + "\t" + Completer.Clean(signature) + "\t"
            + Completer.Documentation(symbol, true));
        return 0;
    }

    public static int Tokens(string[] arguments, TextWriter output)
    {
        if (arguments.Length < 1) return 255;
        Snapshot snapshot = Project.Open(arguments.Skip(1).ToArray(), Path.GetFullPath(arguments[0]));
        if (snapshot.Buffer == null) return 0;
        SemanticModel model = snapshot.Compilation.GetSemanticModel(snapshot.Buffer, false);
        SyntaxNode root = snapshot.Buffer.GetRoot(CancellationToken.None);
        foreach (SyntaxToken token in root.DescendantTokens())
        {
            if (!token.IsKind(SyntaxKind.IdentifierToken) || token.Span.Length == 0) continue;
            bool declared;
            ISymbol symbol;
            try { symbol = SymbolOf(model, token, out declared); }
            catch (Exception) { continue; }
            if (symbol == null) continue;
            IdentifierNameSyntax name = token.Parent as IdentifierNameSyntax;
            string kind = name != null && name.IsVar && symbol is ITypeSymbol ? "keyword" : Kind(symbol);
            if (kind.Length == 0) continue;
            output.WriteLine("tok\t" + token.SpanStart + "\t" + token.Span.Length + "\t" + kind + "\t"
                + Modifiers(symbol, declared));
        }
        return 0;
    }

    // The symbol the caret is on or just after, and whether the caret is on its declaration.
    static ISymbol SymbolAt(Snapshot snapshot, int caret, out bool declared)
    {
        declared = false;
        SyntaxNode root = snapshot.Buffer.GetRoot(CancellationToken.None);
        int end = root.FullSpan.End;
        if (end == 0) return null;
        caret = Math.Max(0, Math.Min(caret, end));
        SyntaxToken token = root.FindToken(Math.Min(caret, end - 1), false);
        // A caret at the end of a name belongs to that name, not to what follows it.
        if (!token.IsKind(SyntaxKind.IdentifierToken) && caret > 0)
        {
            SyntaxToken before = root.FindToken(caret - 1, false);
            if (before.IsKind(SyntaxKind.IdentifierToken)) token = before;
        }
        if (token.Parent == null) return null;
        SemanticModel model = snapshot.Compilation.GetSemanticModel(snapshot.Buffer, false);
        return SymbolOf(model, token, out declared);
    }

    static ISymbol SymbolOf(SemanticModel model, SyntaxToken token, out bool declared)
    {
        declared = false;
        SyntaxNode node = token.Parent;
        if (node == null) return null;
        if (!(node is NameSyntax))
        {
            ISymbol own = model.GetDeclaredSymbol(node, CancellationToken.None);
            if (own != null) { declared = true; return own; }
        }
        SymbolInfo info = model.GetSymbolInfo(node, CancellationToken.None);
        if (info.Symbol != null) return info.Symbol;
        return info.CandidateSymbols.Length > 0 ? info.CandidateSymbols[0] : null;
    }

    // The symbol as written in source: an extension method as declared, a generic one uninstantiated.
    static ISymbol Source(ISymbol symbol)
    {
        IMethodSymbol method = symbol as IMethodSymbol;
        if (method != null && method.ReducedFrom != null) symbol = method.ReducedFrom;
        return symbol.OriginalDefinition ?? symbol;
    }

    // The editor's name for what a symbol is, or empty for one it has no colour for.
    static string Kind(ISymbol symbol)
    {
        if (symbol is INamespaceSymbol) return "namespace";
        if (symbol is ITypeParameterSymbol) return "typeParameter";
        INamedTypeSymbol type = symbol as INamedTypeSymbol;
        if (type != null)
        {
            if (type.TypeKind == TypeKind.Interface) return "interface";
            if (type.TypeKind == TypeKind.Enum) return "enum";
            return "class";
        }
        if (symbol is ITypeSymbol) return "class";
        IMethodSymbol method = symbol as IMethodSymbol;
        if (method != null)
        {
            if (method.MethodKind == MethodKind.Constructor || method.MethodKind == MethodKind.StaticConstructor) return "constructor";
            if (method.MethodKind == MethodKind.LocalFunction) return "function";
            return "method";
        }
        if (symbol is IPropertySymbol || symbol is IEventSymbol) return "property";
        IFieldSymbol field = symbol as IFieldSymbol;
        if (field != null)
        {
            if (field.ContainingType != null && field.ContainingType.TypeKind == TypeKind.Enum) return "enumConstant";
            return field.IsConst ? "constant" : "field";
        }
        if (symbol is IParameterSymbol) return "parameter";
        ILocalSymbol local = symbol as ILocalSymbol;
        if (local != null) return local.IsConst ? "constant" : "localVariable";
        if (symbol is ILabelSymbol) return "label";
        if (symbol is IRangeVariableSymbol) return "localVariable";
        return "";
    }

    static string Modifiers(ISymbol symbol, bool declared)
    {
        var found = new List<string>();
        if (declared) found.Add("declaration");
        bool member = symbol is IMethodSymbol || symbol is IPropertySymbol || symbol is IFieldSymbol || symbol is IEventSymbol;
        if (member && symbol.IsStatic) found.Add("static");
        if (symbol.IsAbstract && !(symbol is INamedTypeSymbol && ((INamedTypeSymbol)symbol).TypeKind == TypeKind.Interface))
            found.Add("abstract");
        IMethodSymbol method = symbol as IMethodSymbol;
        if (method != null && (method.IsExtensionMethod || method.ReducedFrom != null)) found.Add("extension");
        IFieldSymbol field = symbol as IFieldSymbol;
        if (field != null && (field.IsReadOnly || field.IsConst)) found.Add("readonly");
        foreach (AttributeData attribute in symbol.GetAttributes())
        {
            if (attribute.AttributeClass != null && attribute.AttributeClass.Name == "ObsoleteAttribute")
            {
                found.Add("deprecated");
                break;
            }
        }
        return string.Join(",", found);
    }
}
"""
