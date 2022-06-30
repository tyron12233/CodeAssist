package com.tyron.editor.selection;

import com.google.common.collect.Range;
import com.tyron.editor.Editor;
import com.tyron.language.api.Language;
import com.tyron.language.fileTypes.FileTypeManager;
import com.tyron.language.fileTypes.LanguageFileType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class ExpandSelectionProvider {

    private static final Map<Language, ExpandSelectionProvider> sRegisteredProviders =
            new ConcurrentHashMap<>();

    @Nullable
    public abstract Range<Integer> expandSelection(Editor editor);

    public static ExpandSelectionProvider forEditor(Editor editor) {
        File currentFile = editor.getCurrentFile();
        if (currentFile == null) {
            return null;
        }
        LanguageFileType fileType = FileTypeManager.getInstance()
                .findFileType(currentFile);
        if (fileType == null) {
            return null;
        }
        return sRegisteredProviders.get(fileType.getLanguage());
    }

    public static boolean hasProvider(File file) {
        LanguageFileType fileType = FileTypeManager.getInstance()
                .findFileType(file);
        if (fileType == null) {
            return false;
        }
        return sRegisteredProviders.get(fileType.getLanguage()) != null;
    }

    public static void registerProvider(@NotNull Language language, @NotNull ExpandSelectionProvider provider) {
        ExpandSelectionProvider previous =
                sRegisteredProviders.putIfAbsent(language, provider);
        if (previous != null) {
            throw new IllegalArgumentException("ExpandSelectionProvider for " + language + " is already registered.");
        }
    }
}
