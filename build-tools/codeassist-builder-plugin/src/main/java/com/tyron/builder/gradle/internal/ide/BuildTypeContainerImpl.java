package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.gradle.internal.BuildTypeData;
import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceSet;
import com.tyron.builder.core.ComponentTypeImpl;
import com.tyron.builder.model.BuildType;
import com.tyron.builder.model.BuildTypeContainer;
import com.tyron.builder.model.SourceProvider;
import com.tyron.builder.model.SourceProviderContainer;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Immutable
final class BuildTypeContainerImpl implements BuildTypeContainer, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final BuildType buildType;
    @Nullable private final SourceProvider sourceProvider;
    @NonNull
    private final Collection<SourceProviderContainer> extraSourceProviders;

    /**
     * Create a BuildTypeContainer from a BuildTypeData
     *
     * @param buildTypeData the build type data
     * @param includeProdSourceSet whether to include that source set in the model
     * @param includeAndroidTest whether to include that source set in the model
     * @param includeUnitTest whether to include that source set in the model
     * @param sourceProviderContainers collection of extra source providers
     * @return a non-null BuildTypeContainer
     */
    @NonNull
    static BuildTypeContainer create(
            @NonNull BuildTypeData<com.tyron.builder.gradle.internal.dsl.BuildType> buildTypeData,
            boolean includeProdSourceSet,
            boolean includeAndroidTest,
            boolean includeUnitTest,
            @NonNull Collection<SourceProviderContainer> sourceProviderContainers) {

        List<SourceProviderContainer> clonedContainers =
                SourceProviderContainerImpl.cloneCollection(sourceProviderContainers);

        if (includeAndroidTest) {
            DefaultAndroidSourceSet sourceSet =
                    buildTypeData.getTestSourceSet(ComponentTypeImpl.ANDROID_TEST);
            if (sourceSet != null) {
                clonedContainers.add(
                        SourceProviderContainerImpl.create(
                                ComponentTypeImpl.ANDROID_TEST.getArtifactName(), sourceSet));
            }
        }

        if (includeUnitTest) {
            DefaultAndroidSourceSet sourceSet =
                    buildTypeData.getTestSourceSet(ComponentTypeImpl.UNIT_TEST);
            if (sourceSet != null) {
                clonedContainers.add(
                        SourceProviderContainerImpl.create(
                                ComponentTypeImpl.UNIT_TEST.getArtifactName(), sourceSet));
            }
        }

        SourceProviderImpl prodSourceSet = null;
        if (includeProdSourceSet) {
            prodSourceSet = new SourceProviderImpl(buildTypeData.getSourceSet());
        }

        return new BuildTypeContainerImpl(
                new BuildTypeImpl(buildTypeData.getBuildType()), prodSourceSet, clonedContainers);
    }

    private BuildTypeContainerImpl(
            @NonNull BuildTypeImpl buildType,
            @Nullable SourceProviderImpl sourceProvider,
            @NonNull Collection<SourceProviderContainer> extraSourceProviders) {
        this.buildType = buildType;
        this.sourceProvider = sourceProvider;
        this.extraSourceProviders = extraSourceProviders;
    }

    @Override
    @NonNull
    public BuildType getBuildType() {
        return buildType;
    }

    @Override
    @Nullable
    public SourceProvider getSourceProvider() {
        return sourceProvider;
    }

    @NonNull
    @Override
    public Collection<SourceProviderContainer> getExtraSourceProviders() {
        return extraSourceProviders;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BuildTypeContainerImpl that = (BuildTypeContainerImpl) o;
        return Objects.equals(buildType, that.buildType) &&
                Objects.equals(sourceProvider, that.sourceProvider) &&
                Objects.equals(extraSourceProviders, that.extraSourceProviders);
    }

    @Override
    public int hashCode() {
        return Objects.hash(buildType, sourceProvider, extraSourceProviders);
    }
}
