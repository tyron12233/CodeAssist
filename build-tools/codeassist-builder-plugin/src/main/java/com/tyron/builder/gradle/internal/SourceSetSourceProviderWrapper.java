package com.tyron.builder.gradle.internal;

import com.android.annotations.NonNull;
import com.tyron.builder.model.SourceProvider;
import com.tyron.builder.model.v2.CustomSourceDirectory;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.Collection;
import org.gradle.api.tasks.SourceSet;
import org.jetbrains.annotations.NotNull;

/**
 * An implementation of SourceProvider that's wrapper around a Java SourceSet.
 * This is useful for the case where we store SourceProviders but don't want to
 * query the content of the SourceSet at the moment the SourceProvider is created.
 */
public class SourceSetSourceProviderWrapper implements SourceProvider {

    @NonNull
    private final SourceSet sourceSet;

    public SourceSetSourceProviderWrapper(@NonNull SourceSet sourceSet) {

        this.sourceSet = sourceSet;
    }

    @NonNull
    @Override
    public String getName() {
        return sourceSet.getName();
    }

    @NonNull
    @Override
    public File getManifestFile() {
        throw new IllegalAccessError("Shouldn't access manifest from SourceSetSourceProviderWrapper");
    }

    @NonNull
    @Override
    public Collection<File> getJavaDirectories() {
        return sourceSet.getJava().getSrcDirs();
    }

    @NotNull
    @Override
    public Collection<File> getKotlinDirectories() {
        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Collection<File> getResourcesDirectories() {
        return sourceSet.getResources().getSrcDirs();
    }

    @NonNull
    @Override
    public Collection<File> getAidlDirectories() {
        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Collection<File> getRenderscriptDirectories() {
        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Collection<File> getCDirectories() {
        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Collection<File> getCppDirectories() {
        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Collection<File> getResDirectories() {
        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Collection<File> getAssetsDirectories() {
        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Collection<File> getJniLibsDirectories() {
        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Collection<File> getShadersDirectories() {
        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Collection<File> getMlModelsDirectories() {
        return ImmutableList.of();
    }

    @NotNull
    @Override
    public Collection<CustomSourceDirectory> getCustomDirectories() {
        return ImmutableList.of();
    }
}
