package com.tyron.completion.main;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.progress.ProgressManager;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Main entry point for the completions api.
 */
public class CompletionEngine {

    private static CompletionEngine sInstance = null;

    public static CompletionEngine getInstance() {
        if (sInstance == null) {
            sInstance = new CompletionEngine();
        }
        return sInstance;
    }

    private final Map<String, CompletionProvider> mCompletionProviders;

    public CompletionEngine() {
        mCompletionProviders = new HashMap<>();
    }

    public void registerCompletionProvider(CompletionProvider provider) {
        mCompletionProviders.put(provider.getFileExtension(), provider);
    }

    public CompletionProvider getCompletionProvider(String extension) {
        return mCompletionProviders.get(extension);
    }

    public CompletionList complete(Project project,
                                   Module module,
                                   File file,
                                   String contents,
                                   String prefix,
                                   int line,
                                   int column,
                                   long index) {
        String extension = getExtension(file);
        if (ProgressManager.getInstance().isRunning()) {
            ProgressManager.getInstance().setCanceled(true);
        }
        CompletionProvider provider = getCompletionProvider(extension);
        if (provider != null) {
            try {
                ProgressManager.getInstance().setRunning(true);
                ProgressManager.getInstance().setCanceled(false);
                return provider.complete(project, module, file, contents, prefix, line, column, index);
            } catch(ProcessCanceledException e) {
                // ignore
            } {
                ProgressManager.getInstance().setRunning(false);
            }
        }
        return CompletionList.EMPTY;
    }

    private String getExtension(File file) {
        String name = file.getName();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex == -1) {
            return "";
        }
        return name.substring(dotIndex);
    }

    public void clear() {
        mCompletionProviders.clear();
    }
}
