package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.JavaLibrary;
import com.tyron.builder.model.MavenCoordinates;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Serializable implementation of JavaLibrary for use in the model.
 */
@Immutable
public final class JavaLibraryImpl extends LibraryImpl implements JavaLibrary, Serializable {
    private static final long serialVersionUID = 1L;

    private final File jarFile;
    private final List<JavaLibrary> dependencies;

    public JavaLibraryImpl(
            @NonNull File jarFile,
            @Nullable String buildId,
            @Nullable String project,
            @NonNull List<JavaLibrary> dependencies,
            @Nullable MavenCoordinates requestedCoordinates,
            @NonNull MavenCoordinates resolvedCoordinates,
            boolean isSkipped,
            boolean isProvided) {
        super(buildId, project, requestedCoordinates, resolvedCoordinates, isSkipped, isProvided);
        this.jarFile = jarFile;
        this.dependencies = ImmutableList.copyOf(dependencies);
    }

    @NonNull
    @Override
    public File getJarFile() {
        return jarFile;
    }

    @NonNull
    @Override
    public List<? extends JavaLibrary> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        JavaLibraryImpl that = (JavaLibraryImpl) o;
        return Objects.equals(jarFile, that.jarFile) &&
                Objects.equals(dependencies, that.dependencies);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), jarFile, dependencies);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("super", super.toString())
                .add("jarFile", jarFile)
                .add("dependencies", dependencies)
                .toString();
    }
}
