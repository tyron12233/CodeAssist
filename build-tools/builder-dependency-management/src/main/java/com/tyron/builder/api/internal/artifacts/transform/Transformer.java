package com.tyron.builder.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.tyron.builder.api.Describable;
import com.tyron.builder.api.file.FileSystemLocation;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.tasks.FileNormalizer;
import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.work.InputChanges;

import javax.annotation.Nullable;
import java.io.File;

/**
 * The actual code which needs to be executed to transform a file.
 *
 * This encapsulates the public interface {@link com.tyron.builder.api.artifacts.transform.TransformAction} into an internal type.
 */
public interface Transformer extends Describable, TaskDependencyContainer {
    Class<?> getImplementationClass();

    ImmutableAttributes getFromAttributes();

    /**
     * Whether the transformer requires dependencies of the transformed artifact to be injected.
     */
    boolean requiresDependencies();

    /**
     * Whether the transformer requires {@link InputChanges} to be injected.
     */
    boolean requiresInputChanges();

    /**
     * Whether the transformer is cacheable.
     */
    boolean isCacheable();

    ImmutableList<File> transform(Provider<FileSystemLocation> inputArtifactProvider, File outputDir, ArtifactTransformDependencies dependencies, @Nullable InputChanges inputChanges);

    /**
     * The hash of the secondary inputs of the transformer.
     *
     * This includes the parameters and the implementation.
     */
    HashCode getSecondaryInputHash();

    void isolateParametersIfNotAlready();

    Class<? extends FileNormalizer> getInputArtifactNormalizer();

    Class<? extends FileNormalizer> getInputArtifactDependenciesNormalizer();

    boolean isIsolated();

    DirectorySensitivity getInputArtifactDirectorySensitivity();

    DirectorySensitivity getInputArtifactDependenciesDirectorySensitivity();

    LineEndingSensitivity getInputArtifactLineEndingNormalization();

    LineEndingSensitivity getInputArtifactDependenciesLineEndingNormalization();
}
