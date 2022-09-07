package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.Library;
import com.tyron.builder.model.MavenCoordinates;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import java.io.Serializable;

/**
 * Serializable implementation of Library for use in the model.
 */
@Immutable
abstract class LibraryImpl implements Library, Serializable {

    @Nullable private final String buildId;
    @Nullable
    private final String project;
    @Nullable
    private final String name;

    @Nullable
    private final MavenCoordinates requestedCoordinates;
    @NonNull
    private final MavenCoordinates resolvedCoordinates;

    private final boolean isSkipped;
    private final boolean isProvided;

    LibraryImpl(
            @Nullable String buildId,
            @Nullable String project,
            @Nullable MavenCoordinates requestedCoordinates,
            @NonNull MavenCoordinates resolvedCoordinates,
            boolean isSkipped,
            boolean isProvided) {
        this.name = resolvedCoordinates.toString();
        this.buildId = buildId;
        this.project = project;
        this.requestedCoordinates = requestedCoordinates;
        this.resolvedCoordinates = resolvedCoordinates;
        this.isSkipped = isSkipped;
        this.isProvided = isProvided;
    }

    protected LibraryImpl(@NonNull Library clonedLibrary, boolean isSkipped) {
        name = clonedLibrary.getName();
        buildId = clonedLibrary.getBuildId();
        project = clonedLibrary.getProject();
        requestedCoordinates = clonedLibrary.getRequestedCoordinates();
        resolvedCoordinates = clonedLibrary.getResolvedCoordinates();
        this.isSkipped = isSkipped;
        isProvided = clonedLibrary.isProvided();
    }

    @Nullable
    @Override
    public String getBuildId() {
        return buildId;
    }

    @Override
    @Nullable
    public String getProject() {
        return project;
    }

    @Nullable
    @Override
    public String getName() {
        return name;
    }

    @Nullable
    @Override
    public MavenCoordinates getRequestedCoordinates() {
        return requestedCoordinates;
    }

    @NonNull
    @Override
    public MavenCoordinates getResolvedCoordinates() {
        return resolvedCoordinates;
    }

    @Override
    public boolean isSkipped() {
        return isSkipped;
    }

    @Override
    public boolean isProvided() {
        return isProvided;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LibraryImpl library = (LibraryImpl) o;
        return isSkipped == library.isSkipped
                && isProvided == library.isProvided
                && Objects.equal(buildId, library.buildId)
                && Objects.equal(project, library.project)
                && Objects.equal(name, library.name)
                && Objects.equal(requestedCoordinates, library.requestedCoordinates)
                && Objects.equal(resolvedCoordinates, library.resolvedCoordinates);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(
                buildId,
                project,
                name,
                requestedCoordinates,
                resolvedCoordinates,
                isSkipped,
                isProvided);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("buildId", buildId)
                .add("project", project)
                .add("requestedCoordinates", requestedCoordinates)
                .add("resolvedCoordinates", resolvedCoordinates)
                .add("isSkipped", isSkipped)
                .add("isProvided", isProvided)
                .toString();
    }
}
