package com.tyron.completions;

import com.tyron.lang.LanguageExtensionPoint;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginDescriptor;

public class CompletionContributorEP extends LanguageExtensionPoint<CompletionContributor> {

    @TestOnly
    public CompletionContributorEP(@NotNull String language,
                                   @NotNull String implementationClass,
                                   @NotNull PluginDescriptor pluginDescriptor) {
        super(language, implementationClass, pluginDescriptor);
    }
}
