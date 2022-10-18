package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.SourceProvider;
import com.tyron.builder.model.SourceProviderContainer;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Implementation of SourceProviderContainer that is serializable and is meant to be used
 * in the model sent to the tooling API.
 *
 * It also provides convenient methods to create an instance, cloning the original
 * SourceProvider.
 *
 * When the source Provider is cloned, its values are queried and then statically stored.
 * Any further change through the DSL will not be impact. Therefore instances of this class
 * should only be used when the model is built.
 *
 * To create more dynamic isntances of SourceProviderContainer, use
 * {@link com.android.build.gradle.internal.variant.DefaultSourceProviderContainer}
 */
@Immutable
final class SourceProviderContainerImpl implements SourceProviderContainer, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final String name;
    @NonNull
    private final SourceProvider sourceProvider;

    /**
     * Create a {@link SourceProviderContainer} that is serializable to
     * use in the model sent through the tooling API.
     *
     * @param sourceProviderContainer the source provider
     *
     * @return a non-null SourceProviderContainer
     */
    @NonNull
    static SourceProviderContainer clone(
            @NonNull SourceProviderContainer sourceProviderContainer) {
        return create(
                sourceProviderContainer.getArtifactName(),
                sourceProviderContainer.getSourceProvider());
    }

    @NonNull
    static List<SourceProviderContainer> cloneCollection(
            @NonNull Collection<SourceProviderContainer> containers) {
        return containers.stream()
                .map(SourceProviderContainerImpl::clone)
                .collect(Collectors.toList());
    }

    @NonNull
    static SourceProviderContainer create(
            @NonNull String name,
            @NonNull SourceProvider sourceProvider) {
        return new SourceProviderContainerImpl(name, new SourceProviderImpl(sourceProvider));
    }

    private SourceProviderContainerImpl(@NonNull String name,
                                        @NonNull SourceProvider sourceProvider) {
        this.name = name;
        this.sourceProvider = sourceProvider;
    }

    @NonNull
    @Override
    public String getArtifactName() {
        return name;
    }

    @NonNull
    @Override
    public SourceProvider getSourceProvider() {
        return sourceProvider;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SourceProviderContainerImpl that = (SourceProviderContainerImpl) o;
        return Objects.equals(name, that.name) &&
                Objects.equals(sourceProvider, that.sourceProvider);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, sourceProvider);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("sourceProvider", sourceProvider)
                .toString();
    }
}
