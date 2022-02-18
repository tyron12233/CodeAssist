package com.tyron.completion;

import android.annotation.SuppressLint;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.language.api.Language;
import com.tyron.language.fileTypes.FileType;
import com.tyron.language.fileTypes.FileTypeManager;
import com.tyron.language.fileTypes.LanguageFileType;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subclass this to provide completions on the given file.
 * <p>
 * Be sure to frequently call {@link ProgressManager#checkCanceled()} for the
 * user to have a smooth experience because the user may be typing fast and operations
 * may be cancelled at that time.
 */
public abstract class CompletionProvider {

    private static final Multimap<Language, CompletionProvider> sRegisteredCompletionProviders =
            ArrayListMultimap.create();

    public abstract boolean accept(File file);

    public abstract CompletionList complete(CompletionParameters parameters);

    @SuppressLint("NewApi")
    public static ImmutableList<CompletionProvider> forParameters(@NotNull CompletionParameters parameters) {
        File file = parameters.getFile();
        LanguageFileType fileType = FileTypeManager.getInstance()
                .findFileType(file);
        if (fileType == null) {
            return ImmutableList.of();
        }
        Collection<CompletionProvider> providers =
                sRegisteredCompletionProviders.get(fileType.getLanguage());
        if (providers == null) {
            return ImmutableList.of();
        }
        return providers.stream()
                .filter(it -> it.accept(file))
                .collect(ImmutableList.toImmutableList());
    }

    public static ImmutableList<CompletionProvider> forLanguage(@NotNull Language language) {
        return ImmutableList.copyOf(sRegisteredCompletionProviders.get(language));
    }

    public static void registerCompletionProvider(@NotNull Language language, CompletionProvider provider) {
        if (sRegisteredCompletionProviders.containsEntry(language, provider)) {
            return;
        }
        sRegisteredCompletionProviders.put(language, provider);
    }
}
