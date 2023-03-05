package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.api.variant.impl.DirectoryEntry;
import com.tyron.builder.api.variant.impl.SourceDirectoriesImpl;
import com.tyron.builder.api.variant.impl.SourcesImpl;
import com.tyron.builder.model.SourceProvider;
import com.tyron.builder.model.v2.CustomSourceDirectory;
import com.google.common.base.MoreObjects;
import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

/**
 * Implementation of SourceProvider that is serializable. Objects used in the DSL cannot be
 * serialized.
 */
@Immutable
final class SourceProviderImpl implements SourceProvider, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final String name;
    @NonNull
    private final File manifestFile;
    @NonNull
    private final Collection<File> javaDirs;
    @NonNull private final Collection<File> kotlinDirs;
    @NonNull private final Collection<File> resourcesDirs;
    @NonNull
    private final Collection<File> aidlDirs;
    @NonNull
    private final Collection<File> rsDirs;
    @NonNull
    private final Collection<File> resDirs;
    @NonNull
    private final Collection<File> assetsDirs;
    @NonNull
    private final Collection<File> libsDirs;
    @NonNull
    private final Collection<File> shaderDirs;
    @NonNull private final Collection<File> mlModelsDirs;
    @NonNull private final Collection<CustomSourceDirectory> customDirectories;

    public SourceProviderImpl(@NonNull SourceProvider sourceProvider) {
        this.name = sourceProvider.getName();
        this.manifestFile = sourceProvider.getManifestFile();
        this.javaDirs = sourceProvider.getJavaDirectories();
        this.kotlinDirs = sourceProvider.getKotlinDirectories();
        this.resourcesDirs = sourceProvider.getResourcesDirectories();
        this.aidlDirs = sourceProvider.getAidlDirectories();
        this.rsDirs = sourceProvider.getRenderscriptDirectories();
        this.resDirs = sourceProvider.getResDirectories();
        this.assetsDirs = sourceProvider.getAssetsDirectories();
        this.libsDirs = sourceProvider.getJniLibsDirectories();
        this.shaderDirs = sourceProvider.getShadersDirectories();
        this.mlModelsDirs = sourceProvider.getMlModelsDirectories();
        this.customDirectories = sourceProvider.getCustomDirectories();
    }

    /**
     * @param sourceProvider SourceProvider for DSL specified source folders.
     * @param variantSources Variant's {@link com.tyron.builder.api.variant.Sources} if available or
     *     null if there is no variant attached to this instance.
     */
    public SourceProviderImpl(
            @NonNull SourceProvider sourceProvider, @NonNull SourcesImpl variantSources) {
        this.name = sourceProvider.getName();
        this.manifestFile = sourceProvider.getManifestFile();
        this.javaDirs = getSourcesForIdeModel(variantSources.getJava());
        this.kotlinDirs = getSourcesForIdeModel(variantSources.getKotlin());
        this.resourcesDirs = getSourcesForIdeModel(variantSources.getResources());
        this.aidlDirs = sourceProvider.getAidlDirectories();
        this.rsDirs = sourceProvider.getRenderscriptDirectories();
        this.resDirs =
                getSourcesForIdeModel(variantSources.getRes());
        this.assetsDirs = getSourcesForIdeModel(variantSources.getAssets());
        this.libsDirs = getSourcesForIdeModel(variantSources.getJniLibs());
        this.shaderDirs = getSourcesForIdeModel(variantSources.getShaders());
        this.mlModelsDirs = sourceProvider.getMlModelsDirectories();
        this.customDirectories = sourceProvider.getCustomDirectories();
    }

    private Collection<File> getSourcesForIdeModel(SourceDirectoriesImpl sourceDirectories) {
        return sourceDirectories.variantSourcesForModel(DirectoryEntry::getShouldBeAddedToIdeModel);
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    @NonNull
    @Override
    public File getManifestFile() {
        return manifestFile;
    }

    @NonNull
    @Override
    public Collection<File> getJavaDirectories() {
        return javaDirs;
    }

    @NonNull
    @Override
    public Collection<File> getKotlinDirectories() {
        return kotlinDirs;
    }

    @NonNull
    @Override
    public Collection<File> getResourcesDirectories() {
        return resourcesDirs;
    }

    @NonNull
    @Override
    public Collection<File> getAidlDirectories() {
        return aidlDirs;
    }

    @NonNull
    @Override
    public Collection<File> getRenderscriptDirectories() {
        return rsDirs;
    }

    @NonNull
    @Override
    public Collection<File> getCDirectories() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public Collection<File> getCppDirectories() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public Collection<File> getResDirectories() {
        return resDirs;
    }

    @NonNull
    @Override
    public Collection<File> getAssetsDirectories() {
        return assetsDirs;
    }

    @NonNull
    @Override
    public Collection<File> getJniLibsDirectories() {
        return libsDirs;
    }

    @NonNull
    @Override
    public Collection<File> getShadersDirectories() {
        return shaderDirs;
    }

    @NonNull
    @Override
    public Collection<File> getMlModelsDirectories() {
        return mlModelsDirs;
    }

    @NonNull
    @Override
    public Collection<CustomSourceDirectory> getCustomDirectories() {
        return customDirectories;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SourceProviderImpl that = (SourceProviderImpl) o;
        return Objects.equals(name, that.name)
                && Objects.equals(manifestFile, that.manifestFile)
                && Objects.equals(javaDirs, that.javaDirs)
                && Objects.equals(kotlinDirs, that.kotlinDirs)
                && Objects.equals(resourcesDirs, that.resourcesDirs)
                && Objects.equals(aidlDirs, that.aidlDirs)
                && Objects.equals(rsDirs, that.rsDirs)
                && Objects.equals(resDirs, that.resDirs)
                && Objects.equals(assetsDirs, that.assetsDirs)
                && Objects.equals(libsDirs, that.libsDirs)
                && Objects.equals(shaderDirs, that.shaderDirs)
                && Objects.equals(mlModelsDirs, that.mlModelsDirs)
                && Objects.equals(customDirectories, that.customDirectories);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                name,
                manifestFile,
                javaDirs,
                kotlinDirs,
                resourcesDirs,
                aidlDirs,
                rsDirs,
                resDirs,
                assetsDirs,
                libsDirs,
                shaderDirs,
                mlModelsDirs,
                customDirectories);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("manifestFile", manifestFile)
                .add("javaDirs", javaDirs)
                .add("kotlinDirs", kotlinDirs)
                .add("resourcesDirs", resourcesDirs)
                .add("aidlDirs", aidlDirs)
                .add("rsDirs", rsDirs)
                .add("resDirs", resDirs)
                .add("assetsDirs", assetsDirs)
                .add("libsDirs", libsDirs)
                .add("shaderDirs", shaderDirs)
                .add("mlModelsDirs", mlModelsDirs)
                .add("customDirectories", customDirectories)
                .toString();
    }
}
