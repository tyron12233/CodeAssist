package com.tyron.builder.gradle.internal.pipeline;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.api.transform.DirectoryInput;
import com.tyron.builder.api.transform.JarInput;
import com.tyron.builder.api.transform.TransformInput;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.Collection;

/**
 * Immutable version of {@link TransformInput}.
 */
class ImmutableTransformInput implements TransformInput {

    private File optionalRootLocation;
    @NonNull
    private final Collection<JarInput> jarInputs;
    @NonNull
    private final Collection<DirectoryInput> directoryInputs;

    ImmutableTransformInput(
            @NonNull Collection<JarInput> jarInputs,
            @NonNull Collection<DirectoryInput> directoryInputs,
            @Nullable File optionalRootLocation) {
        this.jarInputs = ImmutableList.copyOf(jarInputs);
        this.directoryInputs = ImmutableList.copyOf(directoryInputs);
        this.optionalRootLocation = optionalRootLocation;
    }

    @NonNull
    @Override
    public Collection<JarInput> getJarInputs() {
        return jarInputs;
    }

    @NonNull
    @Override
    public Collection<DirectoryInput> getDirectoryInputs() {
        return directoryInputs;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("rootLocation", optionalRootLocation)
                .add("jarInputs", jarInputs)
                .add("folderInputs", directoryInputs)
                .toString();
    }
}
