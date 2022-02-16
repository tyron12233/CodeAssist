package com.tyron.language.java;

import com.tyron.language.api.Language;

import org.jetbrains.annotations.NotNull;

public class JavaLanguage extends Language {

    public static final JavaLanguage INSTANCE = new JavaLanguage("JAVA");

    protected JavaLanguage(@NotNull String id) {
        super(id);
    }
}
