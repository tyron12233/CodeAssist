package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.BaseArtifact;
import com.tyron.builder.model.Dependencies;
import com.tyron.builder.model.SourceProvider;
import com.tyron.builder.model.level2.DependencyGraphs;
import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import org.gradle.api.file.RegularFile;

/** Implementation of BaseArtifact that is serializable. */
@Immutable
abstract class BaseArtifactImpl implements BaseArtifact, Serializable {

    private static final String NO_MODEL_FILE = "";

    @NonNull private final Collection<File> generatedSourceFolders;
    @NonNull private final String name;
    @NonNull private final String assembleTaskName;
    @NonNull private final String assembleTaskOutputListingFile;
    @NonNull private final String compileTaskName;
    @NonNull private final File classesFolder;
    @NonNull private final File javaResourcesFolder;
    @NonNull private final Dependencies compileDependencies;
    @NonNull private final DependencyGraphs dependencyGraphs;
    @NonNull private final Set<File> additionalClassesFolders;
    @Nullable private final SourceProvider variantSourceProvider;
    @Nullable private final SourceProvider multiFlavorSourceProviders;

    BaseArtifactImpl(
            @NonNull String name,
            @NonNull String assembleTaskName,
            @Nullable RegularFile assembleTaskOutputListingFile,
            @NonNull String compileTaskName,
            @NonNull File classesFolder,
            @NonNull Set<File> additionalClassesFolders,
            @NonNull File javaResourcesFolder,
            @NonNull Dependencies compileDependencies,
            @NonNull DependencyGraphs dependencyGraphs,
            @Nullable SourceProvider variantSourceProvider,
            @Nullable SourceProvider multiFlavorSourceProviders,
            @NonNull Collection<File> generatedSourceFolders) {
        this.name = name;
        this.assembleTaskName = assembleTaskName;
        this.assembleTaskOutputListingFile =
                assembleTaskOutputListingFile != null
                        ? assembleTaskOutputListingFile.getAsFile().getAbsolutePath()
                        : NO_MODEL_FILE;
        this.compileTaskName = compileTaskName;
        this.classesFolder = classesFolder;
        this.additionalClassesFolders = additionalClassesFolders;
        this.javaResourcesFolder = javaResourcesFolder;
        this.compileDependencies = compileDependencies;
        this.dependencyGraphs = dependencyGraphs;
        this.variantSourceProvider = variantSourceProvider;
        this.multiFlavorSourceProviders = multiFlavorSourceProviders;
        this.generatedSourceFolders = generatedSourceFolders;
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    @NonNull
    @Override
    public String getCompileTaskName() {
        return compileTaskName;
    }

    @NonNull
    @Override
    public String getAssembleTaskName() {
        return assembleTaskName;
    }

    @NonNull
    @Override
    public String getAssembleTaskOutputListingFile() {
        return assembleTaskOutputListingFile;
    }

    @NonNull
    @Override
    public File getClassesFolder() {
        return classesFolder;
    }

    @NonNull
    @Override
    public File getJavaResourcesFolder() {
        return javaResourcesFolder;
    }

    @NonNull
    @Override
    public Dependencies getDependencies() {
        return compileDependencies;
    }

    @NonNull
    @Override
    public Dependencies getCompileDependencies() {
        return getDependencies();
    }

    @NonNull
    @Override
    public DependencyGraphs getDependencyGraphs() {
        return dependencyGraphs;
    }

    @Nullable
    @Override
    public SourceProvider getVariantSourceProvider() {
        return variantSourceProvider;
    }

    @Nullable
    @Override
    public SourceProvider getMultiFlavorSourceProvider() {
        return multiFlavorSourceProviders;
    }

    @NonNull
    @Override
    public Collection<File> getGeneratedSourceFolders() {
        return generatedSourceFolders;
    }

    @NonNull
    @Override
    public Set<File> getAdditionalClassesFolders() {
        return additionalClassesFolders;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BaseArtifactImpl that = (BaseArtifactImpl) o;
        return Objects.equals(generatedSourceFolders, that.generatedSourceFolders)
                && Objects.equals(name, that.name)
                && Objects.equals(assembleTaskName, that.assembleTaskName)
                && Objects.equals(assembleTaskOutputListingFile, that.assembleTaskOutputListingFile)
                && Objects.equals(compileTaskName, that.compileTaskName)
                && Objects.equals(classesFolder, that.classesFolder)
                && Objects.equals(additionalClassesFolders, that.additionalClassesFolders)
                && Objects.equals(javaResourcesFolder, that.javaResourcesFolder)
                && Objects.equals(compileDependencies, that.compileDependencies)
                && Objects.equals(dependencyGraphs, that.dependencyGraphs)
                && Objects.equals(variantSourceProvider, that.variantSourceProvider)
                && Objects.equals(multiFlavorSourceProviders, that.multiFlavorSourceProviders);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                generatedSourceFolders,
                name,
                assembleTaskName,
                assembleTaskOutputListingFile,
                compileTaskName,
                classesFolder,
                additionalClassesFolders,
                javaResourcesFolder,
                compileDependencies,
                dependencyGraphs,
                variantSourceProvider,
                multiFlavorSourceProviders);
    }
}
