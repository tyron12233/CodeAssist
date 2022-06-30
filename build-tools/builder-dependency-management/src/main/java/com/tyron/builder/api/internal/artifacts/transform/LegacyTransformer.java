/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.builder.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.file.FileSystemLocation;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.tasks.FileNormalizer;
import com.tyron.builder.internal.fingerprint.AbsolutePathInputNormalizer;
import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.hash.Hashes;
import com.tyron.builder.internal.instantiation.InstantiationScheme;
import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.internal.isolation.IsolatableFactory;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.work.InputChanges;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;

@SuppressWarnings("deprecation")
public class LegacyTransformer extends AbstractTransformer<com.tyron.builder.api.artifacts.transform.ArtifactTransform> {

    private final Instantiator instantiator;
    private final HashCode secondaryInputsHash;
    private final Isolatable<Object[]> isolatableParameters;

    public LegacyTransformer(Class<? extends com.tyron.builder.api.artifacts.transform.ArtifactTransform> implementationClass, Object[] parameters, InstantiationScheme actionInstantiationScheme, ImmutableAttributes fromAttributes, ClassLoaderHierarchyHasher classLoaderHierarchyHasher, IsolatableFactory isolatableFactory) {
        super(implementationClass, fromAttributes);
        this.instantiator = actionInstantiationScheme.instantiator();
        this.isolatableParameters = isolatableFactory.isolate(parameters);
        this.secondaryInputsHash = hashSecondaryInputs(isolatableParameters, implementationClass, classLoaderHierarchyHasher);
    }

    public LegacyTransformer(Class<? extends com.tyron.builder.api.artifacts.transform.ArtifactTransform> implementationClass, Isolatable<Object[]> isolatableParameters, HashCode secondaryInputsHash, InstantiationScheme actionInstantiationScheme, ImmutableAttributes fromAttributes) {
        super(implementationClass, fromAttributes);
        this.instantiator = actionInstantiationScheme.instantiator();
        this.secondaryInputsHash = secondaryInputsHash;
        this.isolatableParameters = isolatableParameters;
    }

    @Override
    public boolean requiresDependencies() {
        return false;
    }

    @Override
    public boolean requiresInputChanges() {
        return false;
    }

    @Override
    public boolean isCacheable() {
        return false;
    }

    public HashCode getSecondaryInputsHash() {
        return secondaryInputsHash;
    }

    public Isolatable<Object[]> getIsolatableParameters() {
        return isolatableParameters;
    }

    @Override
    public ImmutableList<File> transform(Provider<FileSystemLocation> inputArtifactProvider, File outputDir, ArtifactTransformDependencies dependencies, @Nullable InputChanges inputChanges) {
        File inputArtifact = inputArtifactProvider.get().getAsFile();
        com.tyron.builder.api.artifacts.transform.ArtifactTransform transformer = newTransformer();
        transformer.setOutputDirectory(outputDir);
        List<File> outputs = transformer.transform(inputArtifact);
        if (outputs == null) {
            throw new InvalidUserDataException("Transform returned null result.");
        }
        validateOutputs(inputArtifact, outputDir, outputs);
        return ImmutableList.copyOf(outputs);
    }

    private static void validateOutputs(File inputArtifact, File outputDir, List<File> outputs) {
        String inputFilePrefix = inputArtifact.getPath() + File.separator;
        String outputDirPrefix = outputDir.getPath() + File.separator;
        for (File output : outputs) {
            TransformOutputsInternal.validateOutputExists(outputDirPrefix, output);
            TransformOutputsInternal.determineOutputLocationType(output, inputArtifact, inputFilePrefix, outputDir, outputDirPrefix);
        }
    }

    @Override
    public HashCode getSecondaryInputHash() {
        return secondaryInputsHash;
    }

    @Override
    public Class<? extends FileNormalizer> getInputArtifactNormalizer() {
        return AbsolutePathInputNormalizer.class;
    }

    @Override
    public Class<? extends FileNormalizer> getInputArtifactDependenciesNormalizer() {
        return AbsolutePathInputNormalizer.class;
    }

    @Override
    public DirectorySensitivity getInputArtifactDirectorySensitivity() {
        return DirectorySensitivity.DEFAULT;
    }

    @Override
    public DirectorySensitivity getInputArtifactDependenciesDirectorySensitivity() {
        return DirectorySensitivity.DEFAULT;
    }

    @Override
    public LineEndingSensitivity getInputArtifactLineEndingNormalization() {
        return LineEndingSensitivity.DEFAULT;
    }

    @Override
    public LineEndingSensitivity getInputArtifactDependenciesLineEndingNormalization() {
        return LineEndingSensitivity.DEFAULT;
    }

    @Override
    public boolean isIsolated() {
        return true;
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
    }

    @Override
    public void isolateParametersIfNotAlready() {
    }

    private com.tyron.builder.api.artifacts.transform.ArtifactTransform newTransformer() {
        Object[] isolatedParameters = isolatableParameters.isolate();
        return instantiator.newInstance(getImplementationClass(), isolatedParameters);
    }

    private static HashCode hashSecondaryInputs(Isolatable<Object[]> isolatableParameters, Class<? extends com.tyron.builder.api.artifacts.transform.ArtifactTransform> implementationClass, ClassLoaderHierarchyHasher classLoaderHierarchyHasher) {
        Hasher hasher = Hashes.newHasher();
        appendActionImplementation(implementationClass, hasher, classLoaderHierarchyHasher);
        isolatableParameters.appendToHasher(hasher);
        return hasher.hash();
    }
}
