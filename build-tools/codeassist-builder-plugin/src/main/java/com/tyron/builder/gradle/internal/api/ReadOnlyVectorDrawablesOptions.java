package com.tyron.builder.gradle.internal.api;

import com.android.annotations.Nullable;
import com.tyron.builder.model.VectorDrawablesOptions;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

/**
 * Read-only wrapper around another (@link VectorDrawablesOptions}.
 */
public class ReadOnlyVectorDrawablesOptions implements VectorDrawablesOptions {

    private final VectorDrawablesOptions mOptions;

    public ReadOnlyVectorDrawablesOptions(VectorDrawablesOptions options) {
        mOptions = options;
    }

    @Nullable
    @Override
    public Set<String> getGeneratedDensities() {
        if (mOptions.getGeneratedDensities() == null) {
            return null;
        } else {
            return ImmutableSet.copyOf(mOptions.getGeneratedDensities());
        }
    }

    @Nullable
    @Override
    public Boolean getUseSupportLibrary() {
        return mOptions.getUseSupportLibrary();
    }
}
