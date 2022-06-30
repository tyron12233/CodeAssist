/*
 * Copyright 2013 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.internal.artifacts.configurations.MarkConfigurationObservedListener;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.PlatformSupport;
import com.tyron.builder.api.internal.artifacts.ivyservice.DefaultIvyContextManager;
import com.tyron.builder.api.internal.artifacts.ivyservice.IvyContextManager;
import com.tyron.builder.api.internal.artifacts.ivyservice.dependencysubstitution.ModuleSelectorStringNotationConverter;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.DefaultLocalComponentMetadataBuilder;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.LocalComponentMetadataBuilder;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DefaultDependencyDescriptorFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DefaultExcludeRuleConverter;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DefaultLocalConfigurationMetadataBuilder;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DependencyDescriptorFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExcludeRuleConverter;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExternalModuleIvyDependencyDescriptorFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.dependencies.LocalConfigurationMetadataBuilder;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ProjectIvyDependencyDescriptorFactory;
import com.tyron.builder.api.internal.artifacts.transform.ArtifactTransformActionScheme;
import com.tyron.builder.api.internal.artifacts.transform.ArtifactTransformParameterScheme;
import com.tyron.builder.api.internal.artifacts.transform.CacheableTransformTypeAnnotationHandler;
import com.tyron.builder.api.internal.artifacts.transform.InputArtifactAnnotationHandler;
import com.tyron.builder.api.internal.artifacts.transform.InputArtifactDependenciesAnnotationHandler;
import com.tyron.builder.internal.component.external.model.PreferJavaRuntimeVariant;
import com.tyron.builder.internal.resource.transport.file.FileConnectorFactory;

import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.transform.InputArtifact;
import com.tyron.builder.api.artifacts.transform.InputArtifactDependencies;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.api.internal.tasks.properties.InspectionScheme;
import com.tyron.builder.api.internal.tasks.properties.InspectionSchemeFactory;
import com.tyron.builder.api.internal.tasks.properties.annotations.TypeAnnotationHandler;
import com.tyron.builder.api.model.ReplacedBy;
import com.tyron.builder.api.tasks.Classpath;
import com.tyron.builder.api.tasks.CompileClasspath;
import com.tyron.builder.api.tasks.Console;
import com.tyron.builder.api.tasks.IgnoreEmptyDirectories;
import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.InputDirectory;
import com.tyron.builder.api.tasks.InputFile;
import com.tyron.builder.api.tasks.InputFiles;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.Nested;
import com.tyron.builder.api.tasks.Optional;
import com.tyron.builder.api.tasks.PathSensitive;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;
import com.tyron.builder.cache.internal.ProducerGuard;
import com.tyron.builder.internal.instantiation.InstantiationScheme;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.connector.ResourceConnectorFactory;
import com.tyron.builder.internal.resource.local.FileResourceConnector;
import com.tyron.builder.internal.resource.local.FileResourceRepository;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.typeconversion.CrossBuildCachingNotationConverter;
import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.internal.typeconversion.NotationParserBuilder;
import com.tyron.builder.work.Incremental;
import com.tyron.builder.work.NormalizeLineEndings;

class DependencyManagementGlobalScopeServices {
    void configure(ServiceRegistration registration) {
        registration.add(MarkConfigurationObservedListener.class);
    }

    FileResourceRepository createFileResourceRepository(FileSystem fileSystem) {
        return new FileResourceConnector(fileSystem);
    }

    ImmutableModuleIdentifierFactory createModuleIdentifierFactory() {
        return new DefaultImmutableModuleIdentifierFactory();
    }

    NotationParser<Object, ComponentSelector> createComponentSelectorFactory(ImmutableModuleIdentifierFactory moduleIdentifierFactory, CrossBuildInMemoryCacheFactory cacheFactory) {
        return NotationParserBuilder
            .toType(ComponentSelector.class)
            .converter(new CrossBuildCachingNotationConverter<>(new ModuleSelectorStringNotationConverter(moduleIdentifierFactory), cacheFactory.newCache()))
            .toComposite();
    }

    IvyContextManager createIvyContextManager() {
        return new DefaultIvyContextManager();
    }

    ExcludeRuleConverter createExcludeRuleConverter(ImmutableModuleIdentifierFactory moduleIdentifierFactory) {
        return new DefaultExcludeRuleConverter(moduleIdentifierFactory);
    }

    ExternalModuleIvyDependencyDescriptorFactory createExternalModuleDependencyDescriptorFactory(ExcludeRuleConverter excludeRuleConverter) {
        return new ExternalModuleIvyDependencyDescriptorFactory(excludeRuleConverter);
    }

    DependencyDescriptorFactory createDependencyDescriptorFactory(ExcludeRuleConverter excludeRuleConverter, ExternalModuleIvyDependencyDescriptorFactory descriptorFactory) {
        return new DefaultDependencyDescriptorFactory(
            new ProjectIvyDependencyDescriptorFactory(excludeRuleConverter),
            descriptorFactory);
    }

    LocalConfigurationMetadataBuilder createLocalConfigurationMetadataBuilder(DependencyDescriptorFactory dependencyDescriptorFactory,
                                                                              ExcludeRuleConverter excludeRuleConverter) {
        return new DefaultLocalConfigurationMetadataBuilder(dependencyDescriptorFactory, excludeRuleConverter);
    }

    LocalComponentMetadataBuilder createLocalComponentMetaDataBuilder(LocalConfigurationMetadataBuilder localConfigurationMetadataBuilder) {
        return new DefaultLocalComponentMetadataBuilder(localConfigurationMetadataBuilder);
    }

    ResourceConnectorFactory createFileConnectorFactory() {
        return new FileConnectorFactory();
    }

    ProducerGuard<ExternalResourceName> createProducerAccess() {
        return ProducerGuard.adaptive();
    }

    TypeAnnotationHandler createCacheableTransformAnnotationHandler() {
        return new CacheableTransformTypeAnnotationHandler();
    }

    InputArtifactAnnotationHandler createInputArtifactAnnotationHandler() {
        return new InputArtifactAnnotationHandler();
    }

    InputArtifactDependenciesAnnotationHandler createInputArtifactDependenciesAnnotationHandler() {
        return new InputArtifactDependenciesAnnotationHandler();
    }

    PreferJavaRuntimeVariant createPreferJavaRuntimeVariant(NamedObjectInstantiator instantiator) {
        return new PreferJavaRuntimeVariant(instantiator);
    }

    PlatformSupport createPlatformSupport(NamedObjectInstantiator instantiator) {
        return new PlatformSupport(instantiator);
    }

    ArtifactTransformParameterScheme createArtifactTransformParameterScheme(InspectionSchemeFactory inspectionSchemeFactory, InstantiatorFactory instantiatorFactory) {
        InstantiationScheme instantiationScheme = instantiatorFactory.decorateScheme();
        InspectionScheme inspectionScheme = inspectionSchemeFactory.inspectionScheme(
            ImmutableSet.of(
                Console.class,
                Input.class,
                InputDirectory.class,
                InputFile.class,
                InputFiles.class,
                Internal.class,
                Nested.class,
                ReplacedBy.class
            ),
            ImmutableSet.of(
                Classpath.class,
                CompileClasspath.class,
                Incremental.class,
                Optional.class,
                PathSensitive.class,
                IgnoreEmptyDirectories.class,
                NormalizeLineEndings.class
            ),
            instantiationScheme
        );
        return new ArtifactTransformParameterScheme(instantiationScheme, inspectionScheme);
    }

    ArtifactTransformActionScheme createArtifactTransformActionScheme(InspectionSchemeFactory inspectionSchemeFactory, InstantiatorFactory instantiatorFactory) {
        InstantiationScheme instantiationScheme = instantiatorFactory.injectScheme(ImmutableSet.of(
            InputArtifact.class,
            InputArtifactDependencies.class
        ));
        InspectionScheme inspectionScheme = inspectionSchemeFactory.inspectionScheme(
            ImmutableSet.of(
                InputArtifact.class,
                InputArtifactDependencies.class
            ),
            ImmutableSet.of(
                Classpath.class,
                CompileClasspath.class,
                Incremental.class,
                Optional.class,
                PathSensitive.class,
                IgnoreEmptyDirectories.class,
                NormalizeLineEndings.class
            ),
            instantiationScheme
        );
        InstantiationScheme legacyInstantiationScheme = instantiatorFactory.injectScheme();
        return new ArtifactTransformActionScheme(instantiationScheme, inspectionScheme, legacyInstantiationScheme);
    }
}
