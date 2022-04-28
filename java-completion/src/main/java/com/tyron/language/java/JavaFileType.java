package com.tyron.language.java;

import android.graphics.drawable.Drawable;

import com.tyron.language.api.Language;
import com.tyron.language.fileTypes.LanguageFileType;

import org.jetbrains.annotations.NotNull;

public class JavaFileType extends LanguageFileType {

    public static final JavaFileType INSTANCE = new JavaFileType(JavaLanguage.INSTANCE);

    protected JavaFileType(@NotNull Language instance) {
        super(instance);
    }

    @Override
    public @NotNull String getName() {
        return "Java";
    }

    @Override
    public @NotNull String getDescription() {
        return "Java programming language file";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "java";
    }

    @Override
    public @NotNull Drawable getIcon() {
        return null;
    }
}
