package com.tyron.language.fileTypes;

import com.tyron.language.api.Language;

import org.jetbrains.annotations.NotNull;

public abstract class LanguageFileType implements FileType {

    private final Language mLanguage;
    private final boolean mSecondary;

    protected LanguageFileType(@NotNull Language instance) {
        this(instance, false);
    }

    protected LanguageFileType(@NotNull Language instance, boolean secondary) {
        mLanguage = instance;
        mSecondary = secondary;
    }

    @NotNull
    public final Language getLanguage() {
        return mLanguage;
    }

    public boolean isSecondary() {
        return mSecondary;
    }

    @NotNull
    public String getDisplayName() {
        return mLanguage.getDisplayName();
    }
}
