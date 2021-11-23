package com.tyron.kotlin_completion;

import android.util.Log;
import android.util.Pair;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.Project;
import com.tyron.completion.model.CompletionList;
import com.tyron.common.until.Debouncer;
import com.tyron.kotlin_completion.completion.Completions;
import com.tyron.kotlin_completion.diagnostic.ConvertDiagnosticKt;
import com.tyron.kotlin_completion.util.AsyncExecutor;

import org.jetbrains.kotlin.resolve.BindingContext;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;

public class CompletionEngine {

    public enum Recompile {
        ALWAYS, AFTER_DOT, NEVER
    }

    private final Project mProject;

    private final SourcePath sp;
    private final CompilerClassPath classPath;
    private final AsyncExecutor async = new AsyncExecutor();

    private Debouncer debounceLint = new Debouncer(Duration.ofMillis(500));
    private Set<File> lintTodo = new HashSet<>();
    private int lintCount = 0;

    private CompletionEngine(Project project) {
        mProject = project;
        classPath = new CompilerClassPath(project);
        sp = new SourcePath(classPath);
    }

    private static volatile CompletionEngine INSTANCE = null;

    public static synchronized CompletionEngine getInstance(Project project) {
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

    public Pair<CompiledFile, Integer> recover(File file, String contents, Recompile recompile, int offset) {
        boolean shouldRecompile = true;
        switch (recompile) {
            case NEVER: shouldRecompile = false; break;
            case ALWAYS:shouldRecompile = true; break;
            case AFTER_DOT: shouldRecompile = offset > 0 && contents.charAt(offset - 1) == '.';
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

    public CompletableFuture<CompletionList> complete(File file,
                                                      String contents,
                                                      int cursor) {
        if (isIndexing()) {
            return CompletableFuture.completedFuture(CompletionList.EMPTY);
        }

        return async.compute(() -> {
            Pair<CompiledFile, Integer> pair = recover(file, contents, Recompile.NEVER, cursor);
            return new Completions().completions(pair.first, cursor, sp.getIndex());
        });
    }

    private List<File> clearLint() {
        List<File> result = new ArrayList<>(lintTodo);
        lintTodo.clear();
        return result;
    }

    public void lintLater(File file) {
        lintTodo.add(file);
        debounceLint.schedule(this::doLint);
    }

    public void lintNow(File file) {
        lintTodo.add(file);
        debounceLint.submitImmediately(this::doLint);
    }

    private Unit doLint(Function0<Boolean> cancelCallback) {
        List<File> files = clearLint();
        BindingContext context = sp.compileFiles(files);
        if (!cancelCallback.invoke()) {
            List<DiagnosticWrapper> diagnosticWrappers =
                    new ArrayList<>();
            context.getDiagnostics().forEach(it -> {
                diagnosticWrappers.addAll(ConvertDiagnosticKt.convertDiagnostic(it));
            });
            Log.d("Lint", "diagnostics: " + diagnosticWrappers);
        }
        lintCount++;
        return Unit.INSTANCE;
    }

}
