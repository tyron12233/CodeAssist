package com.tyron.builder.api.internal.reflect.service.scopes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.Describable;
import com.tyron.builder.api.Generated;
import com.tyron.builder.api.internal.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.instantiation.InstantiationScheme;
import com.tyron.builder.api.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.api.internal.reflect.annotations.TypeAnnotationMetadataStore;
import com.tyron.builder.api.internal.reflect.annotations.impl.DefaultTypeAnnotationMetadataStore;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.tasks.properties.InspectionScheme;
import com.tyron.builder.api.internal.tasks.properties.InspectionSchemeFactory;
import com.tyron.builder.api.internal.tasks.properties.ModifierAnnotationCategory;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;
import com.tyron.builder.api.internal.tasks.properties.TaskScheme;
import com.tyron.builder.api.internal.tasks.properties.annotations.PropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.TypeAnnotationHandler;
import com.tyron.builder.api.model.ReplacedBy;
import com.tyron.builder.api.providers.Property;
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
import com.tyron.builder.api.work.Incremental;
import com.tyron.builder.api.work.NormalizeLineEndings;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;

public class ExecutionGlobalServices {

    @VisibleForTesting
    public static final ImmutableSet<Class<? extends Annotation>> PROPERTY_TYPE_ANNOTATIONS = ImmutableSet.of(
            Console.class,
            Destroys.class,
            Input.class,
//            InputArtifact.class,
//            InputArtifactDependencies.class,
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
//                        CacheableTask.class,
//                        CacheableTransform.class,
//                        DisableCachingByDefault.class,
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
//                        ConfigureUtil.WrappedConfigureAction.class,
                        // DefaultTestTaskReports used by AbstractTestTask extends this class
//                        DefaultNamedDomainObjectSet.class,
                        // Used in gradle-base so it can't have annotations anyway
                        Describable.class
                ),
                ImmutableSet.of(
//                        GroovyObject.class,
                        Object.class
//                        ScriptOrigin.class
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

    PropertyWalker createPropertyWalker(TaskScheme taskScheme) {
        return taskScheme.getInspectionScheme().getPropertyWalker();
    }

    public interface AnnotationHandlerRegistration {
        Collection<Class<? extends Annotation>> getAnnotations();
    }

    interface AnnotationHandlerRegistar {
        void registerPropertyTypeAnnotations(ImmutableSet.Builder<Class<? extends Annotation>> builder);
    }
}
