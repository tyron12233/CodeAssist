package com.tyron.language.api;

import com.tyron.language.fileTypes.LanguageFileType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class Language {

    private static final Map<Class<? extends Language>, Language> sRegisteredLanguages =
            new ConcurrentHashMap<>();
    private static final Map<String, Language> sRegisteredIds = new ConcurrentHashMap<>();

    public static final Language ANY = new Language("") {
        @Override
        public @NotNull String toString() {
            return "Language: ANY";
        }

        @Override
        public @Nullable LanguageFileType getAssociatedFileType() {
            return null;
        }
    };

    private final String mId;

    protected Language(@NotNull String id) {
        mId = id;

        Class<? extends Language> langClass = getClass();
        Language prev = sRegisteredLanguages.putIfAbsent(langClass, this);
        if (prev != null) {
            throw new IllegalStateException("Language of " + langClass + " is already registered.");
        }

        Language prevId = sRegisteredIds.putIfAbsent(id, this);
        if (prevId != null) {
            throw new IllegalStateException("Language with ID " + id + " is already registered.");
        }
    }

    @NotNull
    public static Collection<Language> getRegisteredLanguages() {
        Collection<Language> values = sRegisteredLanguages.values();
        return Collections.unmodifiableCollection(values);
    }

    @NotNull
    public String getId() {
        return mId;
    }

    @Nullable
    public LanguageFileType getAssociatedFileType() {
        return null;
    }

    @NotNull
    public String toString() {
        return "Language: " + mId;
    }

    public String getDisplayName() {
        return getId();
    }
}
