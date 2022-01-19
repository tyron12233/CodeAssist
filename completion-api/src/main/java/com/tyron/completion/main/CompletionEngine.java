package com.tyron.completion.main;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.progress.ProgressManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                                   File file,
                                   String contents,
                                   String prefix,
                                   int line,
                                   int column,
                                   long index) {
        if (ProgressManager.getInstance().isRunning()) {
            ProgressManager.getInstance().setCanceled(true);
        }
        CompletionList list = new CompletionList();
        list.items = new ArrayList<>();

        CompletionParameters parameters = CompletionParameters.builder()
                .setProject(project)
                .setModule(module)
                .setFile(file)
                .setContents(contents)
                .setPrefix(prefix)
                .setLine(line)
                .setColumn(column)
                .setIndex(index)
                .build();

        List<CompletionProvider> providers = getCompletionProviders(file);
        for (CompletionProvider provider : providers) {
            try {
                ProgressManager.getInstance().setRunning(true);
                ProgressManager.getInstance().setCanceled(false);
                CompletionList complete = provider.complete(parameters);
                list.items.addAll(complete.items);
            } catch(ProcessCanceledException e) {
                return list;
            } {
                ProgressManager.getInstance().setRunning(false);
            }
        }
        return list;
    }

    public void clear() {
        mCompletionProviders.clear();
    }
}
