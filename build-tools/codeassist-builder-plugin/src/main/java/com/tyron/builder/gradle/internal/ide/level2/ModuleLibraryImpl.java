package com.tyron.builder.gradle.internal.ide.level2;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.model.level2.Library;
import com.google.common.base.MoreObjects;
import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.Objects;

/**
 */
public final class ModuleLibraryImpl implements Library, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final String address;
    @NonNull private final String buildId;
    @NonNull private final String projectPath;
    @Nullable
    private final String variant;

    public ModuleLibraryImpl(
            @NonNull String address,
            @NonNull String buildId,
            @NonNull String projectPath,
            @Nullable String variant) {
        this.address = address;
        this.buildId = buildId;
        this.projectPath = projectPath;
        this.variant = variant;
    }

    @Override
    public int getType() {
        return LIBRARY_MODULE;
    }

    @NonNull
    @Override
    public String getArtifactAddress() {
        return address;
    }

    @NonNull
    @Override
    public File getArtifact() {
        throw new UnsupportedOperationException(
                "getArtifact() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getBuildId() {
        return buildId;
    }

    @NonNull
    @Override
    public String getProjectPath() {
        return projectPath;
    }

    @Nullable
    @Override
    public String getVariant() {
        return variant;
    }

    @NonNull
    @Override
    public File getFolder() {
        throw new UnsupportedOperationException(
                "getFolder() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getManifest() {
        throw new UnsupportedOperationException(
                "getManifest() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getJarFile() {
        throw new UnsupportedOperationException(
                "getJarFile() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getCompileJarFile() {
        throw new UnsupportedOperationException(
                "getCompileJarFile() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getResFolder() {
        throw new UnsupportedOperationException(
                "getResFolder() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @Nullable
    @Override
    public File getResStaticLibrary() {
        throw new UnsupportedOperationException(
                "getResStaticLibrary() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getAssetsFolder() {
        throw new UnsupportedOperationException(
                "getAssetsFolder() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public Collection<String> getLocalJars() {
        throw new UnsupportedOperationException(
                "getLocalJars() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getJniFolder() {
        throw new UnsupportedOperationException(
                "getJniFolder() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getAidlFolder() {
        throw new UnsupportedOperationException(
                "getAidlFolder() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getRenderscriptFolder() {
        throw new UnsupportedOperationException(
                "getRenderscriptFolder() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getProguardRules() {
        throw new UnsupportedOperationException(
                "getProguardRules() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getLintJar() {
        throw new UnsupportedOperationException(
                "getLintJar() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getExternalAnnotations() {
        throw new UnsupportedOperationException(
                "getExternalAnnotations() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getPublicResources() {
        throw new UnsupportedOperationException(
                "getPublicResources() cannot be called when getType() returns LIBRARY_MODULE");
    }

    @NonNull
    @Override
    public String getSymbolFile() {
        throw new UnsupportedOperationException(
                "getSymbolFile() cannot be called when getType() returns LIBRARY_MODULE");
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ModuleLibraryImpl that = (ModuleLibraryImpl) o;
        return Objects.equals(address, that.address)
                && Objects.equals(buildId, that.buildId)
                && Objects.equals(projectPath, that.projectPath)
                && Objects.equals(variant, that.variant);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, buildId, projectPath, variant);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("address", address)
                .add("buildId", buildId)
                .add("projectPath", projectPath)
                .add("variant", variant)
                .toString();
    }
}
