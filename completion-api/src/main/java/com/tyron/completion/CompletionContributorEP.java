package com.tyron.completion;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.kotlin.com.intellij.lang.LanguageExtensionPoint;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginDescriptor;

@ApiStatus.NonExtendable
public class CompletionContributorEP extends LanguageExtensionPoint<CompletionContributor> {
  /**
   * @deprecated {@link #CompletionContributorEP(String, String, PluginDescriptor)} must be used to ensure that plugin descriptor is set.
   */
  @Deprecated(forRemoval = true)
  public CompletionContributorEP() {
  }

  @TestOnly
  public CompletionContributorEP(@NotNull String language,
                                 @NotNull String implementationClass,
                                 @NotNull PluginDescriptor pluginDescriptor) {
    super(language, implementationClass, pluginDescriptor);
  }
}