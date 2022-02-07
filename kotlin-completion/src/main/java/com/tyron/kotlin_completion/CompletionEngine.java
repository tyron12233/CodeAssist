package com.tyron.kotlin_completion;

import android.util.Log;
import android.util.Pair;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.common.util.Debouncer;
import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.kotlin_completion.completion.Completions;
import com.tyron.kotlin_completion.diagnostic.ConvertDiagnosticKt;
import com.tyron.kotlin_completion.util.AsyncExecutor;
import com.tyron.kotlin_completion.util.StringUtilsKt;

import org.jetbrains.kotlin.diagnostics.Diagnostic;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;

public class CompletionEngine {

    public enum Recompile {
        ALWAYS, AFTER_DOT, NEVER
    }

    private final AndroidModule mProject;

    private final SourcePath sp;
    private final CompilerClassPath classPath;
    private final AsyncExecutor async = new AsyncExecutor();
    private CachedCompletion cachedCompletion;

    private final Debouncer debounceLint = new Debouncer(Duration.ofMillis(500));
    private Set<File> lintTodo = new HashSet<>();
    private int lintCount = 0;

    private CompletionEngine(AndroidModule project) {
        mProject = project;
        classPath = new CompilerClassPath(project);
        sp = new SourcePath(classPath);
    }

    private static volatile CompletionEngine INSTANCE = null;

    public static synchronized CompletionEngine getInstance(AndroidModule project) {
        if (INSTANCE == null) {
            INSTANCE = new CompletionEngine(project);
        } else {
            if (project != INSTANCE.mProject) {
                Log.d("CompletionEngine", "Creating new instance");
                INSTANCE = new CompletionEngine(project);
            }
        }
        return INSTANCE;
    }

    public boolean isIndexing() {
        return sp.getIndex().getIndexing();
    }

    public SourcePath getSourcePath() {
        return sp;
    }

    public synchronized Pair<CompiledFile, Integer> recover(File file, String contents,
                                                            Recompile recompile, int offset) {
        boolean shouldRecompile = true;
        switch (recompile) {
            case NEVER:
                shouldRecompile = false;
                break;
            case ALWAYS:
                shouldRecompile = true;
                break;
            case AFTER_DOT:
                shouldRecompile = offset > 0 && contents.charAt(offset - 1) == '.';
        }
        sp.put(file, contents, false);

        CompiledFile compiled;
        if (shouldRecompile) {
            compiled = sp.currentVersion(file);
        } else {
            compiled = sp.latestCompiledVersion(file);
        }

        return Pair.create(compiled, offset);
    }

    public CompletableFuture<CompletionList> complete(File file, String contents, int cursor) {
        if (isIndexing()) {
            return CompletableFuture.completedFuture(CompletionList.EMPTY);
        }

        return async.compute(() -> {
            Pair<CompiledFile, Integer> pair = recover(file, contents, Recompile.NEVER, cursor);
            return new Completions().completions(pair.first, cursor, sp.getIndex());
        });
    }

    public CompletionList complete(File file, String contents, String prefix, int line,
                                   int column, int cursor) {
        if (isIndexing()) {
            return CompletionList.EMPTY;
        }

        if (isIncrementalCompletion(cachedCompletion, file, prefix, line, column)) {
            String partialIdentifier = partialIdentifier(prefix, prefix.length());
            CompletionList cachedList = cachedCompletion.getCompletionList();
            if (!cachedList.items.isEmpty()) {
                List<CompletionItem> narrowedList = cachedList.items.stream().filter(item -> {
                    String label = item.label;
                    if (label.contains("(")) {
                        label = label.substring(0, label.indexOf('('));
                    }
                    if (label.length() < partialIdentifier.length()) {
                        return false;
                    }
                    return StringUtilsKt.containsCharactersInOrder(label, partialIdentifier, false);
                }).collect(Collectors.toList());
                CompletionList completionList = new CompletionList();
                completionList.items = narrowedList;
                return completionList;
            }
        }

        debounceLint.cancel();

        Pair<CompiledFile, Integer> recover = recover(file, contents, Recompile.NEVER, cursor);
        CompletionList completions = new Completions().completions(recover.first, cursor,
                sp.getIndex());
        String partialIdentifier = partialIdentifier(contents, cursor);
        cachedCompletion = new CachedCompletion(file, line, column, partialIdentifier, completions);
        return completions;
    }

    private String partialIdentifier(String contents, int end) {
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    private boolean isIncrementalCompletion(CachedCompletion cachedCompletion, File file,
                                            String prefix, int line, int column) {
        prefix = partialIdentifier(prefix, prefix.length());

        if (cachedCompletion == null) {
            return false;
        }

        if (!file.equals(cachedCompletion.getFile())) {
            return false;
        }

        if (prefix.endsWith(".")) {
            return false;
        }

        if (cachedCompletion.getLine() != line) {
            return false;
        }

        if (cachedCompletion.getColumn() > column) {
            return false;
        }

        if (!prefix.startsWith(cachedCompletion.getPrefix())) {
            return false;
        }

        if (prefix.length() - cachedCompletion.getPrefix().length() != column - cachedCompletion.getColumn()) {
            return false;
        }

        return true;
    }

    private List<File> clearLint() {
        List<File> result = new ArrayList<>(lintTodo);
        lintTodo.clear();
        return result;
    }

    public interface LintCallback {
        void onLint(List<DiagnosticWrapper> diagnostics);
    }

    public void lintLater(File file, LintCallback callback) {
        lintTodo.add(file);
        debounceLint.schedule(cancelFunction -> {
            callback.onLint(doLint(cancelFunction));
            return Unit.INSTANCE;
        });
    }

    public void lintNow(File file) {
        lintTodo.add(file);
        debounceLint.submitImmediately(cancel -> {
            doLint(cancel);
            return Unit.INSTANCE;
        });
    }

    public void doLint(File file, String contents, LintCallback callback) {
        debounceLint.cancel();
        debounceLint.schedule(cancel -> {
            doLint(file, contents, cancel, callback);
            return Unit.INSTANCE;
        });
    }

    public void doLint(File file, String contents, Function0<Boolean> cancelCallback,
                       LintCallback callback) {
        if (cancelCallback.invoke()) {
            return;
        }

        sp.put(file, contents, false);
        BindingContext context = sp.compileFiles(Collections.singletonList(file));
        if (cancelCallback.invoke()) {
           return;
        }

        List<DiagnosticWrapper> diagnosticWrappers = new ArrayList<>();
        Diagnostics diagnostics = context.getDiagnostics();
        for (Diagnostic it : diagnostics) {
            diagnosticWrappers.addAll(ConvertDiagnosticKt.convertDiagnostic(it));
        }
        lintCount++;
        callback.onLint(diagnosticWrappers);
    }

    public List<DiagnosticWrapper> doLint(Function0<Boolean> cancelCallback) {
        List<File> files = clearLint();
        BindingContext context = sp.compileFiles(files);
        if (!cancelCallback.invoke()) {
            List<DiagnosticWrapper> diagnosticWrappers = new ArrayList<>();
            Diagnostics diagnostics = context.getDiagnostics();
            for (Diagnostic it : diagnostics) {
                diagnosticWrappers.addAll(ConvertDiagnosticKt.convertDiagnostic(it));
            }
            lintCount++;
            return diagnosticWrappers;
        }
        return Collections.emptyList();
    }

}
