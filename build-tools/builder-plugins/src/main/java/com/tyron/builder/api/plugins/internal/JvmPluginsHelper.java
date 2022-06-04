/*
 * Copyright 2019 the original author or authors.
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
package com.tyron.builder.api.plugins.internal;

import static com.tyron.builder.util.internal.TextUtil.camelToKebabCase;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.ConfigurationContainer;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.attributes.Bundling;
import com.tyron.builder.api.attributes.Category;
import com.tyron.builder.api.attributes.DocsType;
import com.tyron.builder.api.attributes.LibraryElements;
import com.tyron.builder.api.attributes.Usage;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.component.AdhocComponentWithVariants;
import com.tyron.builder.api.component.SoftwareComponent;
import com.tyron.builder.api.component.SoftwareComponentContainer;
import com.tyron.builder.api.file.ConfigurableFileCollection;
import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.SourceDirectorySet;
import com.tyron.builder.api.internal.ConventionMapping;
import com.tyron.builder.api.internal.artifacts.configurations.ConfigurationInternal;
import com.tyron.builder.api.internal.artifacts.dsl.LazyPublishArtifact;
import com.tyron.builder.api.internal.artifacts.publish.AbstractPublishArtifact;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.api.internal.plugins.DslObject;
import com.tyron.builder.api.internal.tasks.DefaultSourceSetOutput;
import com.tyron.builder.api.internal.tasks.compile.CompilationSourceDirs;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.plugins.BasePlugin;
import com.tyron.builder.api.plugins.JavaPluginExtension;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.tasks.SourceSet;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.tasks.TaskProvider;
import com.tyron.builder.api.tasks.bundling.Jar;
import com.tyron.builder.api.tasks.compile.AbstractCompile;
import com.tyron.builder.api.tasks.compile.CompileOptions;
import com.tyron.builder.api.tasks.compile.JavaCompile;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.jvm.JavaModuleDetector;
import com.tyron.builder.language.base.plugins.LifecycleBasePlugin;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * Helpers for Jvm plugins. They are in a separate class so that they don't leak
 * into the public API.
 */
public class JvmPluginsHelper {

    /**
     * Adds an API configuration to a source set, so that API dependencies
     * can be declared.
     *
     * @param sourceSet the source set to add an API for
     * @return the created API configuration
     */
    public static Configuration addApiToSourceSet(SourceSet sourceSet,
                                                  ConfigurationContainer configurations) {
        Configuration apiConfiguration =
                maybeCreateInvisibleConfig(configurations, sourceSet.getApiConfigurationName(),
                        "API dependencies for " + sourceSet + ".", false);

        Configuration compileOnlyApiConfiguration = maybeCreateInvisibleConfig(configurations,
                sourceSet.getCompileOnlyApiConfigurationName(),
                "Compile only API dependencies for " + sourceSet + ".", false);

        Configuration apiElementsConfiguration =
                configurations.getByName(sourceSet.getApiElementsConfigurationName());
        apiElementsConfiguration.extendsFrom(apiConfiguration, compileOnlyApiConfiguration);

        Configuration implementationConfiguration =
                configurations.getByName(sourceSet.getImplementationConfigurationName());
        implementationConfiguration.extendsFrom(apiConfiguration);

        Configuration compileOnlyConfiguration =
                configurations.getByName(sourceSet.getCompileOnlyConfigurationName());
        compileOnlyConfiguration.extendsFrom(compileOnlyApiConfiguration);

        return apiConfiguration;
    }

    public static void configureForSourceSet(final SourceSet sourceSet,
                                             final SourceDirectorySet sourceDirectorySet,
                                             AbstractCompile compile,
                                             CompileOptions options,
                                             final BuildProject target) {
        configureForSourceSet(sourceSet, sourceDirectorySet, compile, target);
        configureAnnotationProcessorPath(sourceSet, sourceDirectorySet, options, target);
    }

    private static void configureForSourceSet(final SourceSet sourceSet,
                                              final SourceDirectorySet sourceDirectorySet,
                                              AbstractCompile compile,
                                              final BuildProject target) {
        compile.setDescription("Compiles the " + sourceDirectorySet.getDisplayName() + ".");
        compile.setSource(sourceSet.getJava());

        ConfigurableFileCollection classpath = compile.getProject().getObjects().fileCollection();
        classpath.from((Callable<Object>) () -> sourceSet.getCompileClasspath()
                .plus(target.files(sourceSet.getJava().getClassesDirectory())));

        compile.getConventionMapping().map("classpath", () -> classpath);
    }

    public static void configureAnnotationProcessorPath(final SourceSet sourceSet,
                                                        SourceDirectorySet sourceDirectorySet,
                                                        CompileOptions options,
                                                        final BuildProject target) {
        final ConventionMapping conventionMapping = new DslObject(options).getConventionMapping();
        conventionMapping.map("annotationProcessorPath", sourceSet::getAnnotationProcessorPath);
        String annotationProcessorGeneratedSourcesChildPath =
                "generated/sources/annotationProcessor/" +
                sourceDirectorySet.getName() +
                "/" +
                sourceSet.getName();
        options.getGeneratedSourceOutputDirectory().convention(
                target.getLayout().getBuildDirectory()
                        .dir(annotationProcessorGeneratedSourcesChildPath));
    }

    /***
     * For compatibility with https://plugins.gradle.org/plugin/io.freefair.aspectj
     */
    @SuppressWarnings("unused")
    public static void configureOutputDirectoryForSourceSet(final SourceSet sourceSet,
                                                            final SourceDirectorySet sourceDirectorySet,
                                                            final BuildProject target,
                                                            Provider<? extends AbstractCompile> compileTask,
                                                            Provider<CompileOptions> options) {
        TaskProvider<? extends AbstractCompile> taskProvider = Cast.uncheckedCast(compileTask);
        configureOutputDirectoryForSourceSet(sourceSet, sourceDirectorySet, target, taskProvider,
                options, AbstractCompile::getDestinationDirectory);
    }

    public static void configureOutputDirectoryForSourceSet(final SourceSet sourceSet,
                                                            final SourceDirectorySet sourceDirectorySet,
                                                            final BuildProject target,
                                                            TaskProvider<?
                                                                    extends AbstractCompile> compileTask,
                                                            Provider<CompileOptions> options) {
        configureOutputDirectoryForSourceSet(sourceSet, sourceDirectorySet, target, compileTask,
                options, AbstractCompile::getDestinationDirectory);
    }

    public static <T extends Task> void configureOutputDirectoryForSourceSet(final SourceSet sourceSet,
                                                                             final SourceDirectorySet sourceDirectorySet,
                                                                             final BuildProject target,
                                                                             TaskProvider<T> compileTask,
                                                                             Provider<CompileOptions> options,
                                                                             Function<T,
                                                                                     DirectoryProperty> classesDirectoryExtractor) {
        final String sourceSetChildPath =
                "classes/" + sourceDirectorySet.getName() + "/" + sourceSet.getName();
        sourceDirectorySet.getDestinationDirectory()
                .convention(target.getLayout().getBuildDirectory().dir(sourceSetChildPath));

        DefaultSourceSetOutput sourceSetOutput =
                Cast.cast(DefaultSourceSetOutput.class, sourceSet.getOutput());
        sourceSetOutput.addClassesDir(sourceDirectorySet.getDestinationDirectory());
        sourceSetOutput.registerClassesContributor(compileTask);
        sourceSetOutput.getGeneratedSourcesDirs()
                .from(options.flatMap(CompileOptions::getGeneratedSourceOutputDirectory));
        sourceDirectorySet.compiledBy(compileTask, classesDirectoryExtractor);
    }

    public static void configureJavaDocTask(@Nullable String featureName,
                                            SourceSet sourceSet,
                                            TaskContainer tasks,
                                            @Nullable JavaPluginExtension javaPluginExtension) {
        String javadocTaskName = sourceSet.getJavadocTaskName();
        if (!tasks.getNames().contains(javadocTaskName)) {
//            tasks.register(javadocTaskName, Javadoc.class, javadoc -> {
//                javadoc.setDescription("Generates Javadoc API documentation for the " +
//                (featureName == null ? "main source code." : "'" + featureName + "' feature."));
//                javadoc.setGroup(JavaBasePlugin.DOCUMENTATION_GROUP);
//                javadoc.setClasspath(sourceSet.getOutput().plus(sourceSet.getCompileClasspath()));
//                javadoc.setSource(sourceSet.getAllJava());
//                if (javaPluginExtension != null) {
//                    javadoc.getConventionMapping().map("destinationDir", () ->
//                    javaPluginExtension.getDocsDir().dir(javadocTaskName).get().getAsFile());
//                    javadoc.getModularity().getInferModulePath().convention(javaPluginExtension
//                    .getModularity().getInferModulePath());
//                }
//            });
            // TODO: ADD JAVADOC
        }
    }

    public static void configureDocumentationVariantWithArtifact(String variantName,
                                                                 @Nullable String featureName,
                                                                 String docsType,
                                                                 List<Capability> capabilities,
                                                                 String jarTaskName,
                                                                 Object artifactSource,
                                                                 @Nullable AdhocComponentWithVariants component,
                                                                 ConfigurationContainer configurations,
                                                                 TaskContainer tasks,
                                                                 ObjectFactory objectFactory) {
        Configuration variant = maybeCreateInvisibleConfig(configurations, variantName,
                docsType + " elements for " + (featureName == null ? "main" : featureName) + ".",
                true);
        AttributeContainer attributes = variant.getAttributes();
        attributes.attribute(Usage.USAGE_ATTRIBUTE,
                objectFactory.named(Usage.class, Usage.JAVA_RUNTIME));
        attributes.attribute(Category.CATEGORY_ATTRIBUTE,
                objectFactory.named(Category.class, Category.DOCUMENTATION));
        attributes.attribute(Bundling.BUNDLING_ATTRIBUTE,
                objectFactory.named(Bundling.class, Bundling.EXTERNAL));
        attributes.attribute(DocsType.DOCS_TYPE_ATTRIBUTE,
                objectFactory.named(DocsType.class, docsType));
        capabilities.forEach(variant.getOutgoing()::capability);

        if (!tasks.getNames().contains(jarTaskName)) {
            TaskProvider<Jar> jarTask = tasks.register(jarTaskName, Jar.class, jar -> {
                jar.setDescription("Assembles a jar archive containing the " +
                                   (featureName == null ? "main " + docsType + "." : (docsType +
                                                                                      " of the '" +
                                                                                      featureName +
                                                                                      "' feature" +
                                                                                      ".")));
                jar.setGroup(BasePlugin.BUILD_GROUP);
                jar.from(artifactSource);
                jar.getArchiveClassifier().set(camelToKebabCase(
                        featureName == null ? docsType : (featureName + "-" + docsType)));
            });
            if (tasks.getNames().contains(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)) {
                tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)
                        .configure(task -> task.dependsOn(jarTask));
            }
        }
        TaskProvider<Task> jar = tasks.named(jarTaskName);
        variant.getOutgoing().artifact(new LazyPublishArtifact(jar));
        if (component != null) {
            component.addVariantsFromConfiguration(variant,
                    new JavaConfigurationVariantMapping("runtime", true));
        }
    }

    private static Configuration maybeCreateInvisibleConfig(ConfigurationContainer container,
                                                            String name,
                                                            String description,
                                                            boolean canBeConsumed) {
        Configuration configuration = container.maybeCreate(name);
        configuration.setVisible(false);
        configuration.setDescription(description);
        configuration.setCanBeResolved(false);
        configuration.setCanBeConsumed(canBeConsumed);
        return configuration;
    }

    @Nullable
    public static AdhocComponentWithVariants findJavaComponent(SoftwareComponentContainer components) {
        SoftwareComponent component = components.findByName("java");
        if (component instanceof AdhocComponentWithVariants) {
            return (AdhocComponentWithVariants) component;
        }
        return null;
    }

    public static Action<ConfigurationInternal> configureLibraryElementsAttributeForCompileClasspath(
            boolean javaClasspathPackaging,
            SourceSet sourceSet,
            TaskProvider<JavaCompile> compileTaskProvider,
            ObjectFactory objectFactory) {
        return conf -> {
            AttributeContainerInternal attributes = conf.getAttributes();
            if (!attributes.contains(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE)) {
                String libraryElements;
                // If we are compiling a module, we require JARs of all dependencies as they may potentially include an Automatic-Module-Name
                if (javaClasspathPackaging ||
                    JavaModuleDetector.isModuleSource(
                            compileTaskProvider.get().getModularity().getInferModulePath().get(),
                            CompilationSourceDirs.inferSourceRoots(
                                    (FileTreeInternal) sourceSet.getJava().getAsFileTree()))) {
                    libraryElements = LibraryElements.JAR;
                } else {
                    libraryElements = LibraryElements.CLASSES;
                }
                attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        objectFactory.named(LibraryElements.class, libraryElements));
            }
        };
    }

    /**
     * A custom artifact type which allows the getFile call to be done lazily only when the
     * artifact is actually needed.
     */
    public abstract static class IntermediateJavaArtifact extends AbstractPublishArtifact {
        private final String type;

        public IntermediateJavaArtifact(String type, Object task) {
            super(task);
            this.type = type;
        }

        @Override
        public String getName() {
            return getFile().getName();
        }

        @Override
        public String getExtension() {
            return "";
        }

        @Override
        public String getType() {
            return type;
        }

        @Nullable
        @Override
        public String getClassifier() {
            return null;
        }

        @Override
        public Date getDate() {
            return null;
        }

        @Override
        public boolean shouldBePublished() {
            return false;
        }
    }
}
