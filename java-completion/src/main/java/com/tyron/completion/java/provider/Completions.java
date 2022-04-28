package com.tyron.completion.java.provider;

import static com.tyron.common.util.StringSearch.endsWithParen;
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
import com.tyron.completion.java.util.FileContentFixer;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.progress.ProcessCanceledException;

import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;

/**
 * Main entry point for getting completions
 */
public class Completions {

    public static final int MAX_COMPLETION_ITEMS = 70;
    private static final String TAG = Completions.class.getSimpleName();

    // patterns
    private static final JavacTreePattern.Capture<IdentifierTree> INSIDE_PARAMETERIZED =
            tree(IdentifierTree.class)
                    .withParent(ParameterizedTypeTree.class);
    private static final JavacTreePattern.Capture<IdentifierTree> INSIDE_RETURN =
            tree(IdentifierTree.class)
                    .withParent(ReturnTree.class);
    private static final JavacTreePattern.Capture<IdentifierTree> VARIABLE_NAME =
            tree(IdentifierTree.class)
                    .withParent(JCTree.JCVariableDecl.class);
    private static final JavacTreePattern.Capture<IdentifierTree> SWITCH_CONSTANT =
            tree(IdentifierTree.class)
                    .withParent(CaseTree.class);

    private final JavaCompilerService compiler;

    public Completions(JavaCompilerService compiler) {
        this.compiler = compiler;
    }

    public CompletionList.Builder complete(File file, String fileContents, long index) {
        checkCanceled();

        ParseTask task = compiler.parse(file.toPath(), fileContents);
        CharSequence contents;
        try {
            StringBuilder pruned = new PruneMethodBodies(task.task).scan(task.root, index);
            int end = StringSearch.endOfLine(pruned, (int) index);
            pruned.insert(end, ';');
            if (compiler.compiler.getCurrentContext() != null) {
                contents = new FileContentFixer(compiler.compiler.getCurrentContext()).fixFileContent(
                        pruned);
            } else {
                contents = pruned.toString();
            }
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "Unable to fix file content", e);
            return null;
        }

        String partial = partialIdentifier(contents.toString(), (int) index);
        return compileAndComplete(file, contents.toString(), partial, index);
    }

    private CompletionList.Builder compileAndComplete(File file, String contents,
                                              final String partial,
                                              long cursor) {
        SourceFileObject source = new SourceFileObject(file.toPath(), contents, Instant.now());
        boolean endsWithParen = endsWithParen(contents, (int) cursor);

        checkCanceled();
        if (compiler.getCachedContainer().isWriting()) {
            return null;
        }
        CompilerContainer container = compiler.compile(Collections.singletonList(source));
        try {
            return container.get(task -> {
                TreePath path = new FindCurrentPath(task.task).scan(task.root(), cursor);
                String modifiedPartial = partial;
                if (path.getLeaf()
                            .getKind() == Tree.Kind.IMPORT) {
                    modifiedPartial = StringSearch.qualifiedPartialIdentifier(contents, (int) cursor);
                    if (modifiedPartial.endsWith(FileContentFixer.INJECTED_IDENT)) {
                        modifiedPartial = modifiedPartial.substring(0, modifiedPartial.length() -
                                                                       FileContentFixer.INJECTED_IDENT.length());
                    }
                }
                return getCompletionList(task, path, modifiedPartial, endsWithParen);
            });
        } catch (Throwable e) {
            if (e instanceof ProcessCanceledException) {
                throw e;
            }

            compiler.destroy();
            throw e;
        }
    }

    private CompletionList.Builder getCompletionList(CompileTask task, TreePath path, String partial,
                                             boolean endsWithParen) {
        ProcessingContext context = createProcessingContext(task.task, task.root());
        CompletionList.Builder builder = CompletionList.builder(partial);
        switch (path.getLeaf().getKind()) {
            case IDENTIFIER:
                // suggest only classes on a parameterized tree
                if (INSIDE_PARAMETERIZED.accepts(path.getLeaf(), context)) {
                    new ClassNameCompletionProvider(compiler)
                            .complete(builder, task, path, partial, endsWithParen);
                    break;
                } else if (SWITCH_CONSTANT.accepts(path.getLeaf(), context)) {
                    new SwitchConstantCompletionProvider(compiler)
                            .complete(builder, task, path, partial, endsWithParen);
                }
                new IdentifierCompletionProvider(compiler)
                        .complete(builder, task, path, partial, endsWithParen);
                break;
            case MEMBER_SELECT:
                new MemberSelectCompletionProvider(compiler)
                        .complete(builder, task, path, partial, endsWithParen);
                break;
            case MEMBER_REFERENCE:
                new MemberReferenceCompletionProvider(compiler)
                        .complete(builder, task, path, partial, endsWithParen);
                break;
            case IMPORT:
                new ImportCompletionProvider(compiler)
                        .complete(builder, task, path, partial, endsWithParen);
                break;
            case STRING_LITERAL:
                break;
            case VARIABLE:
                new VariableNameCompletionProvider(compiler)
                        .complete(builder, task, path, partial, endsWithParen);
                break;
            default:
                new KeywordCompletionProvider(compiler)
                        .complete(builder, task, path, partial, endsWithParen);
                break;
        }
        return builder;
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
