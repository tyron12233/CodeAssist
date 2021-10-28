package com.tyron.psi.completion;

import com.tyron.psi.lang.LanguageExtensionPoint;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginDescriptor;

public class CompletionContributorEP extends LanguageExtensionPoint<CompletionContributor> {
    /**
     * @deprecated {@link #CompletionContributorEP(String, String, PluginDescriptor)} must be used to ensure that plugin descriptor is set.
     */
    @Deprecated
    public CompletionContributorEP() {
    }

    @TestOnly
    public CompletionContributorEP(@NotNull String language,
                                   @NotNull String implementationClass,
                                   @NotNull PluginDescriptor pluginDescriptor) {
        super(language, implementationClass, pluginDescriptor);
    }
}