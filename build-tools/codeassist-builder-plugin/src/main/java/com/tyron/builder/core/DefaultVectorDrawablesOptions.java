package com.tyron.builder.core;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.model.VectorDrawablesOptions;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Sets;
import java.io.Serializable;
import java.util.Set;

/**
 * Default implementation of {@link VectorDrawablesOptions}.
 */
public class DefaultVectorDrawablesOptions implements VectorDrawablesOptions, Serializable {

    @Nullable
    private Set<String> mGeneratedDensities;

    @Nullable
    private Boolean mUseSupportLibrary;

    /**
     * @deprecated use {@link com.android.build.gradle.internal.dsl.VectorDrawablesOptions.copyOf}
     */
    @NonNull
    @Deprecated
    public static DefaultVectorDrawablesOptions copyOf(@NonNull VectorDrawablesOptions original) {
        DefaultVectorDrawablesOptions options = new DefaultVectorDrawablesOptions();

        options.setGeneratedDensities(original.getGeneratedDensities());
        options.setUseSupportLibrary(original.getUseSupportLibrary());

        return options;
    }

    @Nullable
    @Override
    public Set<String> getGeneratedDensities() {
        return mGeneratedDensities;
    }

    public void setGeneratedDensities(@Nullable Iterable<String> densities) {
        if (densities == null) {
            mGeneratedDensities = null;
        } else {
            mGeneratedDensities = Sets.newHashSet(densities);
        }
    }

    @Override
    @Nullable
    public Boolean getUseSupportLibrary() {
        return mUseSupportLibrary;
    }

    public void setUseSupportLibrary(@Nullable Boolean useSupportLibrary) {
        mUseSupportLibrary = useSupportLibrary;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("mGeneratedDensities", mGeneratedDensities)
                .add("mUseSupportLibrary", mUseSupportLibrary)
                .toString();
    }
}
