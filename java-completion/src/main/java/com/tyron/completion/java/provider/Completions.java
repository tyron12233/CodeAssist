package com.tyron.completion.java.provider;

import static com.tyron.common.util.StringSearch.endsWithParen;
import static com.tyron.common.util.StringSearch.isQualifiedIdentifierChar;
import static com.tyron.common.util.StringSearch.partialIdentifier;
import static com.tyron.completion.java.util.CompletionItemFactory.classSnippet;
import static com.tyron.completion.java.util.CompletionItemFactory.packageSnippet;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import android.util.Log;

import com.tyron.builder.model.SourceFileObject;
import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.compiler.ParseTask;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;

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

    public static final int MAX_COMPLETION_ITEMS = 50;
    private static final String TAG = Completions.class.getSimpleName();

    private final JavaCompilerService compiler;

    public Completions(JavaCompilerService compiler) {
        this.compiler = compiler;
    }

    public CompletionList complete(File file, String fileContents, long index) {
        checkCanceled();

        ParseTask task = compiler.parse(file.toPath(), fileContents);
        StringBuilder contents;
        try {
            contents = new PruneMethodBodies(task.task).scan(task.root, index);
            int end = StringSearch.endOfLine(contents, (int) index);
            contents.insert(end, ';');
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "Unable to insert semicolon at the end of line, skipping completion", e);
            return new CompletionList();
        }

        String partial = partialIdentifier(contents.toString(), (int) index);
        CompletionList list = compileAndComplete(file, contents.toString(), partial, index);
        sort(list.items,partial);
        return list;
    }

    private void sort(List<CompletionItem> items, String partial) {
        items.sort(Comparator.comparingInt(it -> {
            String label = it.label;
            if (label.contains("(")) {
                label = label.substring(0, label.indexOf('('));
            }
            if (label.length() != partial.length()) {
                return FuzzySearch.ratio(label, partial);
            } else {
                return FuzzySearch.partialRatio(label, partial);
            }
        }));
        Collections.reverse(items);
    }

    private CompletionList compileAndComplete(File file, String contents, String partial,
                                             long cursor) {
        SourceFileObject source = new SourceFileObject(file.toPath(), contents, Instant.now());
        boolean endsWithParen = endsWithParen(contents, (int) cursor);

        checkCanceled();
        CompilerContainer container = compiler.compile(Collections.singletonList(source));
        return container.get(task -> {
            TreePath path = new FindCompletionsAt(task.task).scan(task.root(), cursor);
            return getCompletionList(task, path, partial, endsWithParen);
        });
    }

    private CompletionList getCompletionList(CompileTask task, TreePath path, String partial,
                                             boolean endsWithParen) {
        switch (path.getLeaf().getKind()) {
            case IDENTIFIER:
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
}
