package com.tyron.completion.main;

import android.util.Log;

import com.google.common.base.Throwables;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.common.logging.IdeLog;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.Editor;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Main entry point for the completions api.
 */
public class CompletionEngine {

    private final Logger logger = IdeLog.getCurrentLogger(this);
    private static CompletionEngine sInstance = null;

    public static CompletionEngine getInstance() {
        if (sInstance == null) {
            sInstance = new CompletionEngine();
        }
        return sInstance;
    }

    private final Set<CompletionProvider> mCompletionProviders;

    public CompletionEngine() {
        mCompletionProviders = new HashSet<>();
    }

    public void registerCompletionProvider(CompletionProvider provider) {
        mCompletionProviders.add(provider);
    }

    public List<CompletionProvider> getCompletionProviders(File file) {
        List<CompletionProvider> providers = new ArrayList<>();
        for (CompletionProvider provider : mCompletionProviders) {
            if (provider.accept(file)) {
                providers.add(provider);
            }
        }
        return providers;
    }

    public CompletionList complete(Project project,
                                   Module module,
                                   Editor editor,
                                   File file,
                                   String contents,
                                   String prefix,
                                   int line,
                                   int column,
                                   long index) {
        if (project.isCompiling()) {
            return CompletionList.EMPTY;
        }

        CompletionList list = new CompletionList();
        list.items = new ArrayList<>();

        CompletionParameters parameters = CompletionParameters.builder()
                .setProject(project)
                .setModule(module)
                .setEditor(editor)
                .setFile(file)
                .setContents(contents)
                .setPrefix(prefix)
                .setLine(line)
                .setColumn(column)
                .setIndex(index)
                .build();
        List<CompletionProvider> providers = CompletionProvider.forParameters(parameters);
        for (CompletionProvider provider : providers) {
            try {
                CompletionList complete = provider.complete(parameters);
                if (complete != null) {
                    list.items.addAll(complete.items);
                }
            } catch (Throwable e) {
                if (e instanceof ProcessCanceledException) {
                    throw e;
                }

                String message = "Failed to complete: \n" +
                                 "index: " + index + "\n" +
                                 "prefix: " + prefix + "\n" +
                                 "File: " + file.getName() + "\n" +
                                 "Stack trace: " + Throwables.getStackTraceAsString(e);
                logger.severe(message);
            }
        }
        return list;
    }

    public void clear() {
        mCompletionProviders.clear();
    }
}
