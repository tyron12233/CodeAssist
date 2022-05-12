package com.tyron.builder.internal.scripts;

import com.tyron.builder.scripts.ScriptingLanguage;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Registry of scripting languages.
 */
public final class ScriptingLanguages {

    private static final List<ScriptingLanguage> ALL =
            Collections.unmodifiableList(
                    Arrays.asList(
                            scriptingLanguage(".gradle", null),
                            scriptingLanguage(".gradle.kts", "org.gradle.kotlin.dsl.provider.KotlinScriptPluginFactory")));

    public static List<ScriptingLanguage> all() {
        return ALL;
    }

    private static ScriptingLanguage scriptingLanguage(final String extension, @Nullable final String scriptPluginFactory) {
        return new ScriptingLanguage() {
            @Override
            public String getExtension() {
                return extension;
            }

            @Override
            public String getProvider() {
                return scriptPluginFactory;
            }
        };
    }
}