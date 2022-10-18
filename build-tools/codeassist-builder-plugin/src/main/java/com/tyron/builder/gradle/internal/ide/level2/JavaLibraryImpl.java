package com.tyron.builder.gradle.internal.ide.level2;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.model.level2.Library;
import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.Objects;

/**
 */
public final class JavaLibraryImpl implements Library, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final String address;
    @NonNull
    private final File artifactFile;

    public JavaLibraryImpl(@NonNull String address, @NonNull File artifactFile) {
        this.address = address;
        this.artifactFile = artifactFile;
    }

    @Override
    public int getType() {
        return LIBRARY_JAVA;
    }

    @NonNull
    @Override
    public String getArtifactAddress() {
        return address;
    }

    @NonNull
    @Override
    public File getArtifact() {
        return artifactFile;
    }

    @Nullable
    @Override
    public String getVariant() {
        throw new UnsupportedOperationException(
                "getVariant() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @Nullable
    @Override
    public String getBuildId() {
        throw new UnsupportedOperationException(
                "getBuildId() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getProjectPath() {
        throw new UnsupportedOperationException(
                "getProjectPath() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public File getFolder() {
        throw new UnsupportedOperationException(
                "getFolder() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getManifest() {
        throw new UnsupportedOperationException(
                "getManifest() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getJarFile() {
        throw new UnsupportedOperationException(
                "getJarFile() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getCompileJarFile() {
        throw new UnsupportedOperationException(
                "getCompileJarFile() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getResFolder() {
        throw new UnsupportedOperationException(
                "getResFolder() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @Nullable
    @Override
    public File getResStaticLibrary() {
        throw new UnsupportedOperationException(
                "getResStaticLibrary() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getAssetsFolder() {
        throw new UnsupportedOperationException(
                "getAssetsFolder() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public Collection<String> getLocalJars() {
        throw new UnsupportedOperationException(
                "getLocalJars() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getJniFolder() {
        throw new UnsupportedOperationException(
                "getJniFolder() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getAidlFolder() {
        throw new UnsupportedOperationException(
                "getAidlFolder() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getRenderscriptFolder() {
        throw new UnsupportedOperationException(
                "getRenderscriptFolder() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getProguardRules() {
        throw new UnsupportedOperationException(
                "getProguardRules() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getLintJar() {
        throw new UnsupportedOperationException(
                "getLintJar() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getExternalAnnotations() {
        throw new UnsupportedOperationException(
                "getExternalAnnotations() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getPublicResources() {
        throw new UnsupportedOperationException(
                "getPublicResources() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @NonNull
    @Override
    public String getSymbolFile() {
        throw new UnsupportedOperationException(
                "getSymbolFile() cannot be called when getType() returns LIBRARY_JAVA");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        JavaLibraryImpl that = (JavaLibraryImpl) o;
        return Objects.equals(address, that.address) &&
                Objects.equals(artifactFile, that.artifactFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, artifactFile);
    }




}
