package com.tyron.builder.internal.service.scopes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.Describable;
import com.tyron.builder.api.Generated;
import com.tyron.builder.api.artifacts.transform.CacheableTransform;
import com.tyron.builder.api.artifacts.transform.InputArtifact;
import com.tyron.builder.api.artifacts.transform.InputArtifactDependencies;
import com.tyron.builder.api.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.DefaultNamedDomainObjectSet;
import com.tyron.builder.api.internal.project.taskfactory.DefaultTaskClassInfoStore;
import com.tyron.builder.api.internal.project.taskfactory.TaskClassInfoStore;
import com.tyron.builder.api.tasks.CacheableTask;
import com.tyron.builder.internal.instantiation.DeserializationInstantiator;
import com.tyron.builder.internal.instantiation.InstanceFactory;
import com.tyron.builder.internal.instantiation.InstanceGenerator;
import com.tyron.builder.internal.instantiation.InstantiationScheme;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.reflect.annotations.TypeAnnotationMetadataStore;
import com.tyron.builder.internal.reflect.annotations.impl.DefaultTypeAnnotationMetadataStore;
import com.tyron.builder.internal.service.ServiceLookup;
import com.tyron.builder.internal.scripts.ScriptOrigin;
import com.tyron.builder.api.internal.tasks.properties.InspectionScheme;
import com.tyron.builder.api.internal.tasks.properties.InspectionSchemeFactory;
import com.tyron.builder.api.internal.tasks.properties.ModifierAnnotationCategory;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;
import com.tyron.builder.api.internal.tasks.properties.TaskScheme;
import com.tyron.builder.api.internal.tasks.properties.annotations.CacheableTaskTypeAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.InputDirectoryPropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.InputFilePropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.InputFilesPropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.InputPropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.LocalStatePropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.NestedBeanAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.NoOpPropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.OutputDirectoriesPropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.OutputDirectoryPropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.OutputFilePropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.OutputFilesPropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.PropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.TypeAnnotationHandler;
import com.tyron.builder.api.model.ReplacedBy;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.tasks.Classpath;
import com.tyron.builder.api.tasks.CompileClasspath;
import com.tyron.builder.api.tasks.Console;
import com.tyron.builder.api.tasks.Destroys;
import com.tyron.builder.api.tasks.IgnoreEmptyDirectories;
import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.InputDirectory;
import com.tyron.builder.api.tasks.InputFile;
import com.tyron.builder.api.tasks.InputFiles;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.LocalState;
import com.tyron.builder.api.tasks.Nested;
import com.tyron.builder.api.tasks.Optional;
import com.tyron.builder.api.tasks.OutputDirectories;
import com.tyron.builder.api.tasks.OutputDirectory;
import com.tyron.builder.api.tasks.OutputFile;
import com.tyron.builder.api.tasks.OutputFiles;
import com.tyron.builder.api.tasks.PathSensitive;
import com.tyron.builder.api.tasks.SkipWhenEmpty;
import com.tyron.builder.api.tasks.options.OptionValues;
import com.tyron.builder.util.internal.ConfigureUtil;
import com.tyron.builder.work.DisableCachingByDefault;
import com.tyron.builder.work.Incremental;
import com.tyron.builder.work.NormalizeLineEndings;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import groovy.lang.GroovyObject;

public class ExecutionGlobalServices {

    @VisibleForTesting
    public static final ImmutableSet<Class<? extends Annotation>> PROPERTY_TYPE_ANNOTATIONS = ImmutableSet.of(
            Console.class,
            Destroys.class,
            Input.class,
            InputArtifact.class,
            InputArtifactDependencies.class,
            InputDirectory.class,
            InputFile.class,
            InputFiles.class,
            LocalState.class,
            Nested.class,
            OptionValues.class,
            OutputDirectories.class,
            OutputDirectory.class,
            OutputFile.class,
            OutputFiles.class
    );

    @VisibleForTesting
    public static final ImmutableSet<Class<? extends Annotation>> IGNORED_METHOD_ANNOTATIONS = ImmutableSet.of(
            Internal.class,
            ReplacedBy.class
    );

    AnnotationHandlerRegistar createAnnotationRegistry(List<AnnotationHandlerRegistration> registrations) {
        return builder -> registrations.forEach(registration -> builder.addAll(registration.getAnnotations()));
    }

    TypeAnnotationMetadataStore createAnnotationMetadataStore(CrossBuildInMemoryCacheFactory cacheFactory, AnnotationHandlerRegistar annotationRegistry) {
        ImmutableSet.Builder<Class<? extends Annotation>> builder = ImmutableSet.builder();
        builder.addAll(PROPERTY_TYPE_ANNOTATIONS);
        annotationRegistry.registerPropertyTypeAnnotations(builder);
        return new DefaultTypeAnnotationMetadataStore(
                ImmutableSet.of(
                        CacheableTask.class,
                        CacheableTransform.class,
                        DisableCachingByDefault.class
//                        UntrackedTask.class
                ),
                ModifierAnnotationCategory.asMap(builder.build()),
                ImmutableSet.of(
                        "java",
                        "groovy",
                        "kotlin"
                ),
                ImmutableSet.of(
                        // Used by a nested bean with action in a task, example:
                        // `NestedInputIntegrationTest.implementation of nested closure in decorated bean is tracked`
                        ConfigureUtil.WrappedConfigureAction.class,
                        // DefaultTestTaskReports used by AbstractTestTask extends this class
                        DefaultNamedDomainObjectSet.class,
                        // Used in gradle-base so it can't have annotations anyway
                        Describable.class
                ),
                ImmutableSet.of(
                        GroovyObject.class,
                        Object.class,
                        ScriptOrigin.class
                ),
                ImmutableSet.of(
                        ConfigurableFileCollection.class,
                        Property.class
                ),
                IGNORED_METHOD_ANNOTATIONS,
                method -> method.isAnnotationPresent(Generated.class),
                cacheFactory);
    }

    InspectionSchemeFactory createInspectionSchemeFactory(
            List<TypeAnnotationHandler> typeHandlers,
            List<PropertyAnnotationHandler> propertyHandlers,
            TypeAnnotationMetadataStore typeAnnotationMetadataStore,
            CrossBuildInMemoryCacheFactory cacheFactory
    ) {
        return new InspectionSchemeFactory(typeHandlers, propertyHandlers, typeAnnotationMetadataStore, cacheFactory);
    }

    TaskScheme createTaskScheme(InspectionSchemeFactory inspectionSchemeFactory, InstantiatorFactory instantiatorFactory, AnnotationHandlerRegistar annotationRegistry) {
        InstantiationScheme instantiationScheme = instantiatorFactory.decorateScheme();
        ImmutableSet.Builder<Class<? extends Annotation>> allPropertyTypes = ImmutableSet.builder();
        allPropertyTypes.addAll(ImmutableSet.of(
                Input.class,
                InputFile.class,
                InputFiles.class,
                InputDirectory.class,
                OutputFile.class,
                OutputFiles.class,
                OutputDirectory.class,
                OutputDirectories.class,
                Destroys.class,
                LocalState.class,
                Nested.class,
                Console.class,
                ReplacedBy.class,
                Internal.class,
                OptionValues.class
        ));
        annotationRegistry.registerPropertyTypeAnnotations(allPropertyTypes);
        InspectionScheme inspectionScheme = inspectionSchemeFactory.inspectionScheme(
                allPropertyTypes.build(),
                ImmutableSet.of(
                        Classpath.class,
                        CompileClasspath.class,
                        Incremental.class,
                        Optional.class,
                        PathSensitive.class,
                        SkipWhenEmpty.class,
                        IgnoreEmptyDirectories.class,
                        NormalizeLineEndings.class
                ),
                instantiationScheme);
        return new TaskScheme(instantiationScheme, inspectionScheme);
    }

    TaskClassInfoStore createTaskClassInfoStore(CrossBuildInMemoryCacheFactory cacheFactory) {
        return new DefaultTaskClassInfoStore(cacheFactory);
    }

    PropertyWalker createPropertyWalker(TaskScheme taskScheme) {
        return taskScheme.getInspectionScheme().getPropertyWalker();
    }

    TypeAnnotationHandler createCacheableTaskPropertyAnnotationHandler() {
        return new CacheableTaskTypeAnnotationHandler();
    }


    PropertyAnnotationHandler createConsoleAnnotationHandler() {
        return new NoOpPropertyAnnotationHandler(Console.class);
    }

    PropertyAnnotationHandler createInternalAnnotationHandler() {
        return new NoOpPropertyAnnotationHandler(Internal.class);
    }

    PropertyAnnotationHandler createReplacedByAnnotationHandler() {
        return new NoOpPropertyAnnotationHandler(ReplacedBy.class);
    }

    PropertyAnnotationHandler createOptionValuesAnnotationHandler() {
        return new NoOpPropertyAnnotationHandler(OptionValues.class);
    }

    PropertyAnnotationHandler createInputPropertyAnnotationHandler() {
        return new InputPropertyAnnotationHandler();
    }

    PropertyAnnotationHandler createInputFilePropertyAnnotationHandler() {
        return new InputFilePropertyAnnotationHandler();
    }

    PropertyAnnotationHandler createInputFilesPropertyAnnotationHandler() {
        return new InputFilesPropertyAnnotationHandler();
    }

    PropertyAnnotationHandler createInputDirectoryPropertyAnnotationHandler() {
        return new InputDirectoryPropertyAnnotationHandler();
    }

    OutputFilePropertyAnnotationHandler createOutputFilePropertyAnnotationHandler() {
        return new OutputFilePropertyAnnotationHandler();
    }

    OutputFilesPropertyAnnotationHandler createOutputFilesPropertyAnnotationHandler() {
        return new OutputFilesPropertyAnnotationHandler();
    }

    OutputDirectoryPropertyAnnotationHandler createOutputDirectoryPropertyAnnotationHandler() {
        return new OutputDirectoryPropertyAnnotationHandler();
    }

    OutputDirectoriesPropertyAnnotationHandler createOutputDirectoriesPropertyAnnotationHandler() {
        return new OutputDirectoriesPropertyAnnotationHandler();
    }

    PropertyAnnotationHandler createDestroysPropertyAnnotationHandler() {
        return new DestroysPropertyAnnotationHandler();
    }

    PropertyAnnotationHandler createLocalStatePropertyAnnotationHandler() {
        return new LocalStatePropertyAnnotationHandler();
    }

    PropertyAnnotationHandler createNestedBeanPropertyAnnotationHandler() {
        return new NestedBeanAnnotationHandler();
    }



    public interface AnnotationHandlerRegistration {
        Collection<Class<? extends Annotation>> getAnnotations();
    }

    interface AnnotationHandlerRegistar {
        void registerPropertyTypeAnnotations(ImmutableSet.Builder<Class<? extends Annotation>> builder);
    }
}
