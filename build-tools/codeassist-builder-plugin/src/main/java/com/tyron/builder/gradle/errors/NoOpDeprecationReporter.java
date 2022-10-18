package com.tyron.builder.gradle.errors;

import com.tyron.builder.gradle.options.Option;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NoOpDeprecationReporter implements DeprecationReporter {
    @Override
    public void reportDeprecatedUsage(@NotNull String newDslElement,
                                      @NotNull String oldDslElement,
                                      @NotNull DeprecationTarget deprecationTarget) {

    }

    @Override
    public void reportDeprecatedApi(@Nullable String newApiElement,
                                    @NotNull String oldApiElement,
                                    @NotNull String url,
                                    @NotNull DeprecationTarget deprecationTarget) {

    }

    @Override
    public void reportDeprecatedValue(@NotNull String dslElement,
                                      @NotNull String oldValue,
                                      @Nullable String newValue,
                                      @NotNull DeprecationTarget deprecationTarget) {

    }

    @Override
    public void reportObsoleteUsage(@NotNull String oldDslElement,
                                    @NotNull DeprecationTarget deprecationTarget) {

    }

    @Override
    public void reportRenamedConfiguration(@NotNull String newConfiguration,
                                           @NotNull String oldConfiguration,
                                           @NotNull DeprecationTarget deprecationTarget) {

    }

    @Override
    public void reportDeprecatedConfiguration(@NotNull String newDslElement,
                                              @NotNull String oldConfiguration,
                                              @NotNull DeprecationTarget deprecationTarget) {

    }

    @Override
    public void reportOptionIssuesIfAny(@NotNull Option<?> option, @NotNull Object value) {

    }
}
