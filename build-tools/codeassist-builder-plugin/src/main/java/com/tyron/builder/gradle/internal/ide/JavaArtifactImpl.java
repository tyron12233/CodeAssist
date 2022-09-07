package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.gradle.internal.ide.dependencies.LibraryDependencyCacheBuildService;
import com.tyron.builder.gradle.internal.ide.dependencies.LibraryUtils;
import com.tyron.builder.model.Dependencies;
import com.tyron.builder.model.JavaArtifact;
import com.tyron.builder.model.SourceProvider;
import com.tyron.builder.model.level2.DependencyGraphs;
import com.google.common.base.Objects;
import com.google.common.collect.Sets;
import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.Set;

/**
 * Implementation of JavaArtifact that is serializable
 */
@Immutable
public final class JavaArtifactImpl extends BaseArtifactImpl implements JavaArtifact, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull private final Set<String> ideSetupTaskNames;
    @Nullable private final File mockablePlatformJar;

    public static JavaArtifactImpl clone(
            @NonNull JavaArtifact javaArtifact,
            int modelLevel,
            boolean modelWithFullDependency,
            @NonNull LibraryDependencyCacheBuildService libraryDependencyCache) {
        SourceProvider variantSP = javaArtifact.getVariantSourceProvider();
        SourceProvider flavorsSP = javaArtifact.getMultiFlavorSourceProvider();

        return new JavaArtifactImpl(
                javaArtifact.getName(),
                javaArtifact.getAssembleTaskName(),
                javaArtifact.getCompileTaskName(),
                javaArtifact.getIdeSetupTaskNames(),
                javaArtifact.getGeneratedSourceFolders(),
                javaArtifact.getClassesFolder(),
                javaArtifact.getAdditionalClassesFolders(),
                javaArtifact.getJavaResourcesFolder(),
                javaArtifact.getMockablePlatformJar(),
                LibraryUtils.clone(javaArtifact.getDependencies(), modelLevel),
                libraryDependencyCache.clone(
                        javaArtifact.getDependencyGraphs(), modelLevel, modelWithFullDependency),
                variantSP != null ? new SourceProviderImpl(variantSP) : null,
                flavorsSP != null ? new SourceProviderImpl(flavorsSP) : null);
    }

    public JavaArtifactImpl(
            @NonNull String name,
            @NonNull String assembleTaskName,
            @NonNull String compileTaskName,
            @NonNull Iterable<String> ideSetupTaskNames,
            @NonNull Collection<File> generatedSourceFolders,
            @NonNull File classesFolder,
            @NonNull Set<File> additionalClassesFolders,
            @NonNull File javaResourcesFolder,
            @Nullable File mockablePlatformJar,
            @NonNull Dependencies compileDependencies,
            @NonNull DependencyGraphs dependencyGraphs,
            @Nullable SourceProvider variantSourceProvider,
            @Nullable SourceProvider multiFlavorSourceProviders) {
        super(
                name,
                assembleTaskName,
                null /* assembleTaskOutputListingFile */,
                compileTaskName,
                classesFolder,
                additionalClassesFolders,
                javaResourcesFolder,
                compileDependencies,
                dependencyGraphs,
                variantSourceProvider,
                multiFlavorSourceProviders,
                generatedSourceFolders);
        this.mockablePlatformJar = mockablePlatformJar;
        this.ideSetupTaskNames = Sets.newHashSet(ideSetupTaskNames);
    }

    @NonNull
    @Override
    public Set<String> getIdeSetupTaskNames() {
        return ideSetupTaskNames;
    }

    @Override
    @Nullable
    public File getMockablePlatformJar() {
        return mockablePlatformJar;
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
        JavaArtifactImpl that = (JavaArtifactImpl) o;
        return Objects.equal(ideSetupTaskNames, that.ideSetupTaskNames)
                && Objects.equal(mockablePlatformJar, that.mockablePlatformJar);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), ideSetupTaskNames, mockablePlatformJar);
    }
}
