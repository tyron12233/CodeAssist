package com.tyron.completion.java.provider;

import static com.tyron.common.util.StringSearch.endsWithParen;
import static com.tyron.common.util.StringSearch.isQualifiedIdentifierChar;
import static com.tyron.common.util.StringSearch.partialIdentifier;
import static com.tyron.completion.java.patterns.JavacTreePatterns.tree;
import static com.tyron.completion.java.util.CompletionItemFactory.classSnippet;
import static com.tyron.completion.java.util.CompletionItemFactory.packageSnippet;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import android.util.Log;

import com.tyron.builder.model.SourceFileObject;
import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.compiler.ParseTask;
import com.tyron.completion.java.patterns.JavacTreePattern;
import com.tyron.completion.java.patterns.JavacTreePatterns;
import com.tyron.completion.java.util.FileContentFixer;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.IdentifierTree;
import org.openjdk.source.tree.ParameterizedTypeTree;
import org.openjdk.source.tree.ReturnTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.parser.ScannerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import me.xdrop.fuzzywuzzy.FuzzySearch;

/**
 * Main entry point for getting completions
 */
public class Completions {

    public static final int MAX_COMPLETION_ITEMS = 300;
    private static final String TAG = Completions.class.getSimpleName();

    // patterns
    private static final JavacTreePattern.Capture<IdentifierTree> INSIDE_PARAMETERIZED =
            tree(IdentifierTree.class)
                    .withParent(ParameterizedTypeTree.class);
    private static final JavacTreePattern.Capture<IdentifierTree> INSIDE_RETURN =
            tree(IdentifierTree.class)
                    .withParent(ReturnTree.class);

    private final JavaCompilerService compiler;

    public Completions(JavaCompilerService compiler) {
        this.compiler = compiler;
    }

    public CompletionList complete(File file, String fileContents, long index) {
        checkCanceled();

        ParseTask task = compiler.parse(file.toPath(), fileContents);
        CharSequence contents;
        try {
            StringBuilder pruned = new PruneMethodBodies(task.task).scan(task.root, index);
            int end = StringSearch.endOfLine(pruned, (int) index);
            pruned.insert(end, ';');
            contents = pruned;
            contents = new FileContentFixer(compiler.compiler.getCurrentContext())
                    .fixFileContent(pruned);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "Unable to fix file content", e);
            return new CompletionList();
        }

        String partial = partialIdentifier(contents.toString(), (int) index);
        CompletionList list = compileAndComplete(file, contents.toString(), partial, index);
        sort(list.items, partial);
        return list;
    }

    private void sort(List<CompletionItem> items, String partial) {
        items.sort(Comparator.comparingInt(it -> {
            String label = it.label;
            if (label.contains("(")) {
                label = label.substring(0, label.indexOf('('));
            }

            return FuzzySearch.ratio(label, partial);
        }));
        Collections.reverse(items);
    }

    private CompletionList compileAndComplete(File file, String contents,
                                              final String partial,
                                              long cursor) {
        SourceFileObject source = new SourceFileObject(file.toPath(), contents, Instant.now());
        boolean endsWithParen = endsWithParen(contents, (int) cursor);

        checkCanceled();
        CompilerContainer container = compiler.compile(Collections.singletonList(source));
        return container.get(task -> {
            TreePath path = new FindCompletionsAt(task.task).scan(task.root(), cursor);
            String modifiedPartial = partial;
            if (path.getLeaf().getKind() == Tree.Kind.IMPORT) {
                modifiedPartial = StringSearch.qualifiedPartialIdentifier(contents, (int) cursor);
                if (modifiedPartial.endsWith(FileContentFixer.INJECTED_IDENT)) {
                    modifiedPartial = modifiedPartial.substring(0, modifiedPartial.length() - FileContentFixer.INJECTED_IDENT.length());
                }
            }
            return getCompletionList(task, path, modifiedPartial, endsWithParen);
        });
    }

    private CompletionList getCompletionList(CompileTask task, TreePath path, String partial,
                                             boolean endsWithParen) {
        ProcessingContext context = createProcessingContext(task.task, task.root());
        switch (path.getLeaf().getKind()) {
            case IDENTIFIER:
                // suggest only classes on a parameterized tree
                if (INSIDE_PARAMETERIZED.accepts(path.getLeaf(), context)) {
                    return new ClassNameCompletionProvider(compiler)
                            .complete(task, path, partial, endsWithParen);
                }
                return new IdentifierCompletionProvider(compiler)
                        .complete(task, path, partial, endsWithParen);
            case MEMBER_SELECT:
                return new MemberSelectCompletionProvider(compiler)
                        .complete(task, path, partial, endsWithParen);
            case MEMBER_REFERENCE:
                return new MemberReferenceCompletionProvider(compiler)
                        .complete(task, path, partial, endsWithParen);
            case CASE:
                return new SwitchConstantCompletionProvider(compiler)
                        .complete(task, path, partial, endsWithParen);
            case IMPORT:
                return new ImportCompletionProvider(compiler)
                        .complete(task, path, partial, endsWithParen);
            case STRING_LITERAL:
                return CompletionList.EMPTY;
            default:
                return new KeywordCompletionProvider(compiler)
                        .complete(task, path, partial, endsWithParen);
        }
    }

    private void addTopLevelSnippets(ParseTask task, CompletionList list) {
        Path file = Paths.get(task.root.getSourceFile().toUri());
        if (!hasTypeDeclaration(task.root)) {
            list.items.add(classSnippet(file));
            if (task.root.getPackageName() == null) {
                list.items.add(packageSnippet(file));
            }
        }
    }

    private boolean hasTypeDeclaration(CompilationUnitTree root) {
        for (Tree tree : root.getTypeDecls()) {
            if (tree.getKind() != Tree.Kind.ERRONEOUS) {
                return true;
            }
        }
        return false;
    }

    private ProcessingContext createProcessingContext(JavacTask task, CompilationUnitTree root) {
        ProcessingContext context = new ProcessingContext();
        context.put("trees", Trees.instance(task));
        context.put("root", root);
        return context;
    }
}
