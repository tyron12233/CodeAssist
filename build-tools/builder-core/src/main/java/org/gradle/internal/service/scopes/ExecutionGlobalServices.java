package org.gradle.internal.service.scopes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import org.gradle.api.Describable;
import org.gradle.api.Generated;
import org.gradle.api.artifacts.transform.CacheableTransform;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.InputArtifactDependencies;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.internal.DefaultNamedDomainObjectSet;
import org.gradle.api.internal.project.taskfactory.DefaultTaskClassInfoStore;
import org.gradle.api.internal.project.taskfactory.TaskClassInfoStore;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.internal.instantiation.InstantiationScheme;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.reflect.annotations.TypeAnnotationMetadataStore;
import org.gradle.internal.reflect.annotations.impl.DefaultTypeAnnotationMetadataStore;
import org.gradle.internal.service.ServiceLookup;
import org.gradle.internal.scripts.ScriptOrigin;
import org.gradle.api.internal.tasks.properties.InspectionScheme;
import org.gradle.api.internal.tasks.properties.InspectionSchemeFactory;
import org.gradle.api.internal.tasks.properties.ModifierAnnotationCategory;
import org.gradle.api.internal.tasks.properties.PropertyWalker;
import org.gradle.api.internal.tasks.properties.TaskScheme;
import org.gradle.api.internal.tasks.properties.annotations.CacheableTaskTypeAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.InputDirectoryPropertyAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.InputFilePropertyAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.InputFilesPropertyAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.InputPropertyAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.LocalStatePropertyAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.NestedBeanAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.NoOpPropertyAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.OutputDirectoriesPropertyAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.OutputDirectoryPropertyAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.OutputFilePropertyAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.OutputFilesPropertyAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.PropertyAnnotationHandler;
import org.gradle.api.internal.tasks.properties.annotations.TypeAnnotationHandler;
import org.gradle.api.model.ReplacedBy;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.Console;
import org.gradle.api.tasks.Destroys;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectories;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.OutputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.options.OptionValues;
import org.gradle.util.internal.ConfigureUtil;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.work.Incremental;
import org.gradle.work.NormalizeLineEndings;
import org.gradle.cache.internal.CrossBuildInMemoryCacheFactory;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;

import groovy.lang.GroovyObject;

@SuppressWarnings("unused")
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
                        DisableCachingByDefault.class,
                        UntrackedTask.class
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
