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
 * - `@@outline`: `item <kind> <depth> <name offset> <end> <name> <detail>` for each declaration, then
 *   `fold <start> <end> <kind> <placeholder>` for each region that can be collapsed. Syntax only.
 * - `@@references`: `sym <name> <start> <end> <kind> <in source 0|1>` for the name at the caret, then
 *   `ref <path> <start> <end> <d|w|r>` for each place the project declares, writes or reads that symbol.
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

    fun outline(
        sdk: DotnetSdk,
        bufferPath: String,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? = ask(sdk, listOf("@@outline", bufferPath) + arguments, cancellation, onLine)

    fun references(
        sdk: DotnetSdk,
        bufferPath: String,
        caret: Int,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? = ask(sdk, listOf("@@references", caret.toString(), bufferPath) + arguments, cancellation, onLine)

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
        SyntaxToken token;
        return SymbolAt(snapshot, caret, out declared, out token);
    }

    static ISymbol SymbolAt(Snapshot snapshot, int caret, out bool declared, out SyntaxToken at)
    {
        declared = false;
        at = default(SyntaxToken);
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
        at = token;
        SemanticModel model = snapshot.Compilation.GetSemanticModel(snapshot.Buffer, false);
        return SymbolOf(model, token, out declared);
    }

    // ---- usages

    public static int References(string[] arguments, TextWriter output)
    {
        if (arguments.Length < 2) return 255;
        Snapshot snapshot = Project.Open(arguments.Skip(2).ToArray(), Path.GetFullPath(arguments[1]));
        if (snapshot.Buffer == null) return 0;
        bool declared;
        SyntaxToken token;
        ISymbol symbol = SymbolAt(snapshot, int.Parse(arguments[0]), out declared, out token);
        if (symbol == null || !token.IsKind(SyntaxKind.IdentifierToken)) return 0;
        ISymbol wanted = Source(symbol);
        // A name that stands for a constructor is a use of its type: `new Cart()` and `class Cart` go together.
        IMethodSymbol constructor = wanted as IMethodSymbol;
        if (constructor != null && (constructor.MethodKind == MethodKind.Constructor || constructor.MethodKind == MethodKind.StaticConstructor))
            wanted = constructor.ContainingType.OriginalDefinition;

        bool inSource = wanted.Locations.Any(l => l.IsInSource);
        string name = token.ValueText;
        output.WriteLine("sym\t" + Completer.Clean(name) + "\t" + token.SpanStart + "\t" + token.Span.End + "\t" + Kind(wanted) + "\t" + (inSource ? "1" : "0"));

        foreach (SyntaxTree tree in snapshot.Compilation.SyntaxTrees)
        {
            if (tree.GetText(CancellationToken.None).ToString().IndexOf(name, StringComparison.Ordinal) < 0) continue;
            SemanticModel model = snapshot.Compilation.GetSemanticModel(tree, false);
            foreach (SyntaxToken candidate in tree.GetRoot(CancellationToken.None).DescendantTokens())
            {
                if (!candidate.IsKind(SyntaxKind.IdentifierToken) || candidate.ValueText != name) continue;
                bool own;
                ISymbol found;
                try { found = SymbolOf(model, candidate, out own); }
                catch (Exception) { continue; }
                if (found == null) continue;
                ISymbol source = Source(found);
                IMethodSymbol made = source as IMethodSymbol;
                if (made != null && made.MethodKind == MethodKind.Constructor && wanted is INamedTypeSymbol)
                    source = made.ContainingType.OriginalDefinition;
                if (!SymbolEqualityComparer.Default.Equals(source, wanted)) continue;
                string access = own ? "d" : Written(candidate) ? "w" : "r";
                output.WriteLine("ref\t" + tree.FilePath + "\t" + candidate.SpanStart + "\t" + candidate.Span.End + "\t" + access);
            }
        }
        return 0;
    }

    // Whether a use of a name assigns to it: the left of an assignment, `++` or `--`, an `out` or `ref` argument.
    static bool Written(SyntaxToken token)
    {
        SyntaxNode expression = token.Parent;
        MemberAccessExpressionSyntax member = expression == null ? null : expression.Parent as MemberAccessExpressionSyntax;
        if (member != null && member.Name == expression) expression = member;
        SyntaxNode parent = expression == null ? null : expression.Parent;
        AssignmentExpressionSyntax assignment = parent as AssignmentExpressionSyntax;
        if (assignment != null) return assignment.Left == expression;
        if (parent is PrefixUnaryExpressionSyntax || parent is PostfixUnaryExpressionSyntax)
            return parent.IsKind(SyntaxKind.PreIncrementExpression) || parent.IsKind(SyntaxKind.PreDecrementExpression)
                || parent.IsKind(SyntaxKind.PostIncrementExpression) || parent.IsKind(SyntaxKind.PostDecrementExpression);
        ArgumentSyntax argument = parent as ArgumentSyntax;
        return argument != null && (argument.RefKindKeyword.IsKind(SyntaxKind.OutKeyword) || argument.RefKindKeyword.IsKind(SyntaxKind.RefKeyword));
    }

    // ---- outline and folding

    public static int Outline(string[] arguments, TextWriter output)
    {
        if (arguments.Length < 1) return 255;
        Snapshot snapshot = Project.Open(arguments.Skip(1).ToArray(), Path.GetFullPath(arguments[0]));
        if (snapshot.Buffer == null) return 0;
        SyntaxNode root = snapshot.Buffer.GetRoot(CancellationToken.None);
        Items(root, 0, output);
        Folds(root, snapshot.Buffer, output);
        return 0;
    }

    static void Item(TextWriter output, string kind, int depth, SyntaxToken name, SyntaxNode node, string detail)
    {
        if (name.Span.Length == 0) return;
        output.WriteLine("item\t" + kind + "\t" + depth + "\t" + name.SpanStart + "\t" + node.Span.End + "\t"
            + Completer.Clean(name.ValueText) + "\t" + Completer.Clean(detail ?? ""));
    }

    static void Items(SyntaxNode node, int depth, TextWriter output)
    {
        foreach (SyntaxNode child in node.ChildNodes())
        {
            BaseNamespaceDeclarationSyntax space = child as BaseNamespaceDeclarationSyntax;
            if (space != null)
            {
                // A file-scoped namespace holds the whole file; indenting everything under it says nothing.
                bool whole = child is FileScopedNamespaceDeclarationSyntax;
                output.WriteLine("item\tnamespace\t" + depth + "\t" + space.Name.SpanStart + "\t" + child.Span.End + "\t"
                    + Completer.Clean(space.Name.ToString()) + "\t");
                Items(child, whole ? depth : depth + 1, output);
                continue;
            }
            TypeDeclarationSyntax type = child as TypeDeclarationSyntax;
            if (type != null)
            {
                Item(output, type is InterfaceDeclarationSyntax ? "interface" : "class", depth, type.Identifier, child, "");
                Items(child, depth + 1, output);
                continue;
            }
            EnumDeclarationSyntax enumeration = child as EnumDeclarationSyntax;
            if (enumeration != null)
            {
                Item(output, "enum", depth, enumeration.Identifier, child, "");
                foreach (EnumMemberDeclarationSyntax member in enumeration.Members)
                    Item(output, "enumConstant", depth + 1, member.Identifier, member, "");
                continue;
            }
            DelegateDeclarationSyntax callback = child as DelegateDeclarationSyntax;
            if (callback != null) { Item(output, "class", depth, callback.Identifier, child, callback.ParameterList.ToString()); continue; }
            MethodDeclarationSyntax method = child as MethodDeclarationSyntax;
            if (method != null) { Item(output, "method", depth, method.Identifier, child, method.ParameterList.ToString()); continue; }
            ConstructorDeclarationSyntax constructor = child as ConstructorDeclarationSyntax;
            if (constructor != null) { Item(output, "constructor", depth, constructor.Identifier, child, constructor.ParameterList.ToString()); continue; }
            PropertyDeclarationSyntax property = child as PropertyDeclarationSyntax;
            if (property != null) { Item(output, "property", depth, property.Identifier, child, property.Type.ToString()); continue; }
            EventDeclarationSyntax raised = child as EventDeclarationSyntax;
            if (raised != null) { Item(output, "property", depth, raised.Identifier, child, raised.Type.ToString()); continue; }
            BaseFieldDeclarationSyntax field = child as BaseFieldDeclarationSyntax;
            if (field != null)
            {
                bool constant = field.Modifiers.Any(m => m.IsKind(SyntaxKind.ConstKeyword));
                foreach (VariableDeclaratorSyntax variable in field.Declaration.Variables)
                    Item(output, constant ? "constant" : "field", depth, variable.Identifier, child, field.Declaration.Type.ToString());
            }
        }
    }

    static void Fold(TextWriter output, SyntaxTree tree, int start, int end, string kind, string placeholder)
    {
        if (end <= start) return;
        var span = tree.GetLineSpan(Microsoft.CodeAnalysis.Text.TextSpan.FromBounds(start, end), CancellationToken.None);
        if (span.StartLinePosition.Line == span.EndLinePosition.Line) return;
        output.WriteLine("fold\t" + start + "\t" + end + "\t" + kind + "\t" + Completer.Clean(placeholder));
    }

    static void Folds(SyntaxNode root, SyntaxTree tree, TextWriter output)
    {
        var usings = root.DescendantNodes(n => n is CompilationUnitSyntax || n is BaseNamespaceDeclarationSyntax)
            .OfType<UsingDirectiveSyntax>().ToList();
        if (usings.Count > 1) Fold(output, tree, usings[0].SpanStart, usings[usings.Count - 1].Span.End, "imports", "using ...");

        foreach (SyntaxNode node in root.DescendantNodes())
        {
            BaseTypeDeclarationSyntax type = node as BaseTypeDeclarationSyntax;
            if (type != null) { Fold(output, tree, type.OpenBraceToken.SpanStart, type.CloseBraceToken.Span.End, "classBody", "{...}"); continue; }
            NamespaceDeclarationSyntax space = node as NamespaceDeclarationSyntax;
            if (space != null) { Fold(output, tree, space.OpenBraceToken.SpanStart, space.CloseBraceToken.Span.End, "block", "{...}"); continue; }
            AccessorListSyntax accessors = node as AccessorListSyntax;
            if (accessors != null) { Fold(output, tree, accessors.OpenBraceToken.SpanStart, accessors.CloseBraceToken.Span.End, "functionBody", "{...}"); continue; }
            BlockSyntax block = node as BlockSyntax;
            if (block != null)
            {
                bool body = block.Parent is BaseMethodDeclarationSyntax || block.Parent is AccessorDeclarationSyntax
                    || block.Parent is LocalFunctionStatementSyntax || block.Parent is AnonymousFunctionExpressionSyntax;
                Fold(output, tree, block.OpenBraceToken.SpanStart, block.CloseBraceToken.Span.End, body ? "functionBody" : "block", "{...}");
            }
        }

        var regions = new Stack<SyntaxTrivia>();
        foreach (SyntaxTrivia trivia in root.DescendantTrivia(null, true))
        {
            if (trivia.IsKind(SyntaxKind.MultiLineCommentTrivia)) Fold(output, tree, trivia.SpanStart, trivia.Span.End, "comment", "/*...*/");
            else if (trivia.IsKind(SyntaxKind.SingleLineDocumentationCommentTrivia))
                Fold(output, tree, trivia.SpanStart, trivia.Span.End - (trivia.ToFullString().EndsWith("\n") ? 1 : 0), "comment", "/// ...");
            else if (trivia.IsKind(SyntaxKind.RegionDirectiveTrivia)) regions.Push(trivia);
            else if (trivia.IsKind(SyntaxKind.EndRegionDirectiveTrivia) && regions.Count > 0)
            {
                SyntaxTrivia opened = regions.Pop();
                Fold(output, tree, opened.SpanStart, trivia.Span.End, "block", opened.ToString().Trim());
            }
        }
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
