package com.tyron.kotlin_completion;

import android.util.Log;
import android.util.Pair;

import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.completion.model.CompletionList;
import com.tyron.kotlin_completion.completion.Completions;
import com.tyron.kotlin_completion.util.AsyncExecutor;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;

public class CompletionEngine {

    private enum Recompile {
        ALWAYS, AFTER_DOT, NEVER
    }

    private final Project mProject;

    private final SourcePath sp;
    private final CompilerClassPath classPath;
    private final AsyncExecutor async = new AsyncExecutor();

    private CompletionEngine(Project project) {
        mProject = project;
        classPath = new CompilerClassPath(project);
        sp = new SourcePath(classPath);
    }

    private static CompletionEngine INSTANCE = null;

    public static CompletionEngine getInstance(Project project) {
        if (INSTANCE == null) {
            INSTANCE = new CompletionEngine(project);
        } else {
            if (project != INSTANCE.mProject) {
                INSTANCE = new CompletionEngine(project);
            }
        }
        return INSTANCE;
    }

    private Pair<CompiledFile, Integer> recover(File file, String contents, Recompile recompile, int offset) {
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

    public CompletionList complete(File file, String contents, int cursor) {
        Pair<CompiledFile, Integer> pair = recover(file, contents, Recompile.AFTER_DOT, cursor);
        return new Completions().completions(pair.first, cursor, sp.getIndex());
    }
}
