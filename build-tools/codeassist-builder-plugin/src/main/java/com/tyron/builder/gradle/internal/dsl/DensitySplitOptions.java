package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.android.resources.Density;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.tyron.builder.gradle.internal.dsl.decorator.annotation.WithLazyInitialization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;

public abstract class DensitySplitOptions extends SplitOptions
        implements com.tyron.builder.api.dsl.DensitySplit {

    @Inject
    @WithLazyInitialization(methodName = "lazyInit")
    public DensitySplitOptions() {}

    protected void lazyInit() {
        setStrict(true);
        init();
    }

    @Override
    protected Set<String> getDefaultValues() {
        Set<Density> values = Density.getRecommendedValuesForDevice();
        Set<String> fullList = Sets.newHashSetWithExpectedSize(values.size());
        for (Density value : values) {
            fullList.add(value.getResourceValue());
        }

        return fullList;
    }

    @Override
    protected ImmutableSet<String> getAllowedValues() {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();

        for (Density value : Density.values()) {
            if (value != Density.NODPI && value != Density.ANYDPI) {
                builder.add(value.getResourceValue());
            }
        }

        return builder.build();
    }

    public void setCompatibleScreens(@NonNull List<String> sizes) {
        ArrayList newValues = new ArrayList(sizes);
        getCompatibleScreens().clear();
        getCompatibleScreens().addAll(newValues);
    }

    @Override
    public void compatibleScreens(@NonNull String... sizes) {
        getCompatibleScreens().addAll(Arrays.asList(sizes));
    }
    /**
     * Sets whether the build system should determine the splits based on the density folders
     * in the resources.
     *
     * <p>If the auto mode is set to true, the include list will be ignored.
     *
     * @param auto true to automatically set the splits list based on the folders presence, false
     *             to use the include list.
     *
     * @deprecated DensitySplitOptions.auto is not supported anymore.
     */
    @Deprecated
    public void setAuto(boolean auto) {
        throw new RuntimeException("DensitySplitOptions.auto is not supported anymore.");
    }
}
