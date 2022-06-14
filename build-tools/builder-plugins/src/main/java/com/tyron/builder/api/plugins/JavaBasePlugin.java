/*
 * Copyright 2010 the original author or authors.
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

package com.tyron.builder.api.plugins;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.JavaVersion;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.ConfigurationContainer;
import com.tyron.builder.api.artifacts.type.ArtifactTypeDefinition;
import com.tyron.builder.api.file.SourceDirectorySet;
import com.tyron.builder.api.internal.ConventionMapping;
import com.tyron.builder.api.internal.IConventionAware;
import com.tyron.builder.api.internal.artifacts.configurations.ConfigurationInternal;
import com.tyron.builder.api.internal.plugins.DslObject;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.plugins.internal.DefaultJavaPluginConvention;
import com.tyron.builder.api.plugins.internal.DefaultJavaPluginExtension;
import com.tyron.builder.api.plugins.internal.JvmPluginsHelper;
import com.tyron.builder.api.plugins.jvm.internal.JvmEcosystemUtilities;
import com.tyron.builder.api.plugins.jvm.internal.JvmPluginServices;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.reporting.DirectoryReport;
import com.tyron.builder.api.tasks.SourceSet;
import com.tyron.builder.api.tasks.SourceSetContainer;
import com.tyron.builder.api.tasks.TaskProvider;
import com.tyron.builder.api.tasks.compile.AbstractCompile;
import com.tyron.builder.api.tasks.compile.JavaCompile;
import com.tyron.builder.api.tasks.testing.JUnitXmlReport;
import com.tyron.builder.api.tasks.testing.Test;
import com.tyron.builder.internal.deprecation.DeprecatableConfiguration;
import com.tyron.builder.jvm.toolchain.JavaToolchainService;
import com.tyron.builder.jvm.toolchain.JavaToolchainSpec;
import com.tyron.builder.jvm.toolchain.internal.DefaultJavaToolchainService;
import com.tyron.builder.jvm.toolchain.internal.DefaultToolchainSpec;
import com.tyron.builder.jvm.toolchain.internal.JavaToolchainQueryService;
import com.tyron.builder.jvm.toolchain.internal.ToolchainSpecInternal;
import com.tyron.builder.language.base.plugins.LifecycleBasePlugin;
import com.tyron.builder.language.jvm.tasks.ProcessResources;

import java.io.File;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import javax.inject.Inject;

/**
 * <p>A {@link com.tyron.builder.api.Plugin} which compiles and tests Java source, and assembles
 * it into a JAR file.</p>
 *
 * @see
 * <a href="https://docs.gradle.org/current/userguide/java_plugin.html">Java plugin reference</a>
 */
public class JavaBasePlugin implements Plugin<BuildProject> {
    public static final String CHECK_TASK_NAME = LifecycleBasePlugin.CHECK_TASK_NAME;

    public static final String VERIFICATION_GROUP = LifecycleBasePlugin.VERIFICATION_GROUP;
    public static final String BUILD_TASK_NAME = LifecycleBasePlugin.BUILD_TASK_NAME;
    public static final String BUILD_DEPENDENTS_TASK_NAME = "buildDependents";
    public static final String BUILD_NEEDED_TASK_NAME = "buildNeeded";
    public static final String DOCUMENTATION_GROUP = "documentation";

    /**
     * Set this property to use JARs build from subprojects, instead of the classes folder from
     * these project, on the compile classpath.
     * The main use case for this is to mitigate performance issues on very large multi-projects
     * building on Windows.
     * Setting this property will cause the 'jar' task of all subprojects in the dependency tree
     * to always run during compilation.
     *
     * @since 5.6
     */
    public static final String COMPILE_CLASSPATH_PACKAGING_SYSTEM_PROPERTY =
            "com.tyron.builder.java.compile-classpath-packaging";

    /**
     * A list of known artifact types which are known to prevent from
     * publication.
     *
     * @since 5.3
     */
    public static final Set<String> UNPUBLISHABLE_VARIANT_ARTIFACTS = ImmutableSet
            .of(ArtifactTypeDefinition.JVM_CLASS_DIRECTORY,
                    ArtifactTypeDefinition.JVM_RESOURCES_DIRECTORY,
                    ArtifactTypeDefinition.DIRECTORY_TYPE);

    private final boolean javaClasspathPackaging;
    private final JvmPluginServices jvmPluginServices;

    @Inject
    public JavaBasePlugin(JvmEcosystemUtilities jvmPluginServices) {
        this.javaClasspathPackaging =
                Boolean.getBoolean(COMPILE_CLASSPATH_PACKAGING_SYSTEM_PROPERTY);
        this.jvmPluginServices = (JvmPluginServices) jvmPluginServices;
    }

    @Override
    public void apply(final BuildProject project) {
        ProjectInternal projectInternal = (ProjectInternal) project;

        project.getPluginManager().apply(BasePlugin.class);
        project.getPluginManager().apply(JvmEcosystemPlugin.class);
        project.getPluginManager().apply(ReportingBasePlugin.class);

        DefaultJavaPluginExtension javaPluginExtension = addExtensions(projectInternal);

        configureSourceSetDefaults(project, javaPluginExtension);
        configureCompileDefaults(project, javaPluginExtension);

        configureJavaDoc(project, javaPluginExtension);
        configureTest(project, javaPluginExtension);
        configureBuildNeeded(project);
        configureBuildDependents(project);
    }

    private DefaultJavaPluginExtension addExtensions(final ProjectInternal project) {
        DefaultToolchainSpec toolchainSpec =
                project.getObjects().newInstance(DefaultToolchainSpec.class);
        SourceSetContainer sourceSets =
                (SourceSetContainer) project.getExtensions().getByName("sourceSets");
        DefaultJavaPluginExtension javaPluginExtension =
                (DefaultJavaPluginExtension) project.getExtensions()
                        .create(JavaPluginExtension.class, "java", DefaultJavaPluginExtension.class,
                                project, sourceSets, toolchainSpec, jvmPluginServices);
        project.getConvention().getPlugins()
                .put("java", new DefaultJavaPluginConvention(project, javaPluginExtension));
        project.getExtensions().create(JavaToolchainService.class, "javaToolchains",
                DefaultJavaToolchainService.class, getJavaToolchainQueryService());
        return javaPluginExtension;
    }

    private void configureSourceSetDefaults(BuildProject project,
                                            final JavaPluginExtension javaPluginExtension) {
        javaPluginExtension.getSourceSets().all(sourceSet -> {
            ConventionMapping outputConventionMapping =
                    ((IConventionAware) sourceSet.getOutput()).getConventionMapping();

            ConfigurationContainer configurations = project.getConfigurations();

            defineConfigurationsForSourceSet(sourceSet, configurations);
            definePathsForSourceSet(sourceSet, outputConventionMapping, project);

            createProcessResourcesTask(sourceSet, sourceSet.getResources(), project);
            TaskProvider<JavaCompile> compileTask =
                    createCompileJavaTask(sourceSet, sourceSet.getJava(), project);
            createClassesTask(sourceSet, project);

            configureLibraryElements(compileTask, sourceSet, configurations, project.getObjects());
            configureTargetPlatform(compileTask, sourceSet, configurations);

            JvmPluginsHelper
                    .configureOutputDirectoryForSourceSet(sourceSet, sourceSet.getJava(), project,
                            compileTask, compileTask.map(JavaCompile::getOptions));
        });
    }

    private void configureLibraryElements(TaskProvider<JavaCompile> compileTaskProvider,
                                          SourceSet sourceSet,
                                          ConfigurationContainer configurations,
                                          ObjectFactory objectFactory) {
        Action<ConfigurationInternal> configureLibraryElements = JvmPluginsHelper
                .configureLibraryElementsAttributeForCompileClasspath(javaClasspathPackaging,
                        sourceSet, compileTaskProvider, objectFactory);
        ((ConfigurationInternal) configurations
                .getByName(sourceSet.getCompileClasspathConfigurationName()))
                .beforeLocking(configureLibraryElements);
    }

    private void configureTargetPlatform(TaskProvider<JavaCompile> compileTask,
                                         SourceSet sourceSet,
                                         ConfigurationContainer configurations) {
        jvmPluginServices.useDefaultTargetPlatformInference(
                configurations.getByName(sourceSet.getCompileClasspathConfigurationName()),
                compileTask);
        jvmPluginServices.useDefaultTargetPlatformInference(
                configurations.getByName(sourceSet.getRuntimeClasspathConfigurationName()),
                compileTask);
    }

    private TaskProvider<JavaCompile> createCompileJavaTask(final SourceSet sourceSet,
                                                            final SourceDirectorySet sourceDirectorySet,
                                                            final BuildProject target) {
        return target.getTasks()
                .register(sourceSet.getCompileJavaTaskName(), JavaCompile.class, compileTask -> {
                    compileTask.setDescription("Compiles " + sourceDirectorySet + ".");
                    compileTask.setSource(sourceDirectorySet);
                    ConventionMapping conventionMapping = compileTask.getConventionMapping();
                    conventionMapping.map("classpath", sourceSet::getCompileClasspath);
                    JvmPluginsHelper.configureAnnotationProcessorPath(sourceSet, sourceDirectorySet,
                            compileTask.getOptions(), target);
                    String generatedHeadersDir = "generated/sources/headers/" +
                                                 sourceDirectorySet.getName() +
                                                 "/" +
                                                 sourceSet.getName();
                    compileTask.getOptions().getHeaderOutputDirectory().convention(
                            target.getLayout().getBuildDirectory().dir(generatedHeadersDir));
                    JavaPluginExtension javaPluginExtension =
                            target.getExtensions().getByType(JavaPluginExtension.class);
                    compileTask.getModularity().getInferModulePath()
                            .convention(javaPluginExtension.getModularity().getInferModulePath());
                    compileTask.getJavaCompiler().convention(
                            getToolchainTool(target, JavaToolchainService::compilerFor));
                });
    }

    private void createProcessResourcesTask(final SourceSet sourceSet,
                                            final SourceDirectorySet resourceSet,
                                            final BuildProject target) {
        target.getTasks().register(sourceSet.getProcessResourcesTaskName(), ProcessResources.class,
                resourcesTask -> {
                    resourcesTask.setDescription("Processes " + resourceSet + ".");
                    new DslObject(resourcesTask.getRootSpec()).getConventionMapping()
                            .map("destinationDir",
                                    (Callable<File>) () -> sourceSet.getOutput().getResourcesDir());
                    resourcesTask.from(resourceSet);
                });
    }

    private void createClassesTask(final SourceSet sourceSet, BuildProject target) {
        sourceSet.compiledBy(
                target.getTasks().register(sourceSet.getClassesTaskName(), classesTask -> {
                    classesTask.setGroup(LifecycleBasePlugin.BUILD_GROUP);
                    classesTask.setDescription("Assembles " + sourceSet.getOutput() + ".");
                    classesTask.dependsOn(sourceSet.getOutput().getDirs());
                    classesTask.dependsOn(sourceSet.getCompileJavaTaskName());
                    classesTask.dependsOn(sourceSet.getProcessResourcesTaskName());
                }));
    }

    private void definePathsForSourceSet(final SourceSet sourceSet,
                                         ConventionMapping outputConventionMapping,
                                         final BuildProject project) {
        outputConventionMapping.map("resourcesDir", () -> {
            String classesDirName = "resources/" + sourceSet.getName();
            return new File(project.getBuildDir(), classesDirName);
        });

        sourceSet.getJava().srcDir("src/" + sourceSet.getName() + "/java");
        sourceSet.getResources().srcDir("src/" + sourceSet.getName() + "/resources");
    }

    private void defineConfigurationsForSourceSet(SourceSet sourceSet,
                                                  ConfigurationContainer configurations) {
        String implementationConfigurationName = sourceSet.getImplementationConfigurationName();
        String runtimeOnlyConfigurationName = sourceSet.getRuntimeOnlyConfigurationName();
        String compileOnlyConfigurationName = sourceSet.getCompileOnlyConfigurationName();
        String compileClasspathConfigurationName = sourceSet.getCompileClasspathConfigurationName();
        String annotationProcessorConfigurationName =
                sourceSet.getAnnotationProcessorConfigurationName();
        String runtimeClasspathConfigurationName = sourceSet.getRuntimeClasspathConfigurationName();
        String sourceSetName = sourceSet.toString();

        Configuration implementationConfiguration =
                configurations.maybeCreate(implementationConfigurationName);
        implementationConfiguration.setVisible(false);
        implementationConfiguration
                .setDescription("Implementation only dependencies for " + sourceSetName + ".");
        implementationConfiguration.setCanBeConsumed(false);
        implementationConfiguration.setCanBeResolved(false);

        DeprecatableConfiguration compileOnlyConfiguration =
                (DeprecatableConfiguration) configurations
                        .maybeCreate(compileOnlyConfigurationName);
        compileOnlyConfiguration.setVisible(false);
        compileOnlyConfiguration.setCanBeConsumed(false);
        compileOnlyConfiguration.setCanBeResolved(false);
        compileOnlyConfiguration
                .setDescription("Compile only dependencies for " + sourceSetName + ".");

        ConfigurationInternal compileClasspathConfiguration = (ConfigurationInternal) configurations
                .maybeCreate(compileClasspathConfigurationName);
        compileClasspathConfiguration.setVisible(false);
        compileClasspathConfiguration
                .extendsFrom(compileOnlyConfiguration, implementationConfiguration);
        compileClasspathConfiguration
                .setDescription("Compile classpath for " + sourceSetName + ".");
        compileClasspathConfiguration.setCanBeConsumed(false);

        jvmPluginServices.configureAsCompileClasspath(compileClasspathConfiguration);

        ConfigurationInternal annotationProcessorConfiguration =
                (ConfigurationInternal) configurations
                        .maybeCreate(annotationProcessorConfigurationName);
        annotationProcessorConfiguration.setVisible(false);
        annotationProcessorConfiguration.setDescription(
                "Annotation processors and their dependencies for " + sourceSetName + ".");
        annotationProcessorConfiguration.setCanBeConsumed(false);
        annotationProcessorConfiguration.setCanBeResolved(true);

        jvmPluginServices.configureAsRuntimeClasspath(annotationProcessorConfiguration);

        Configuration runtimeOnlyConfiguration =
                configurations.maybeCreate(runtimeOnlyConfigurationName);
        runtimeOnlyConfiguration.setVisible(false);
        runtimeOnlyConfiguration.setCanBeConsumed(false);
        runtimeOnlyConfiguration.setCanBeResolved(false);
        runtimeOnlyConfiguration
                .setDescription("Runtime only dependencies for " + sourceSetName + ".");

        ConfigurationInternal runtimeClasspathConfiguration = (ConfigurationInternal) configurations
                .maybeCreate(runtimeClasspathConfigurationName);
        runtimeClasspathConfiguration.setVisible(false);
        runtimeClasspathConfiguration.setCanBeConsumed(false);
        runtimeClasspathConfiguration.setCanBeResolved(true);
        runtimeClasspathConfiguration.setDescription("Runtime classpath of " + sourceSetName + ".");
        runtimeClasspathConfiguration
                .extendsFrom(runtimeOnlyConfiguration, implementationConfiguration);
        jvmPluginServices.configureAsRuntimeClasspath(runtimeClasspathConfiguration);

        sourceSet.setCompileClasspath(compileClasspathConfiguration);
        sourceSet.setRuntimeClasspath(sourceSet.getOutput().plus(runtimeClasspathConfiguration));
        sourceSet.setAnnotationProcessorPath(annotationProcessorConfiguration);

        compileClasspathConfiguration.deprecateForDeclaration(implementationConfigurationName,
                compileOnlyConfigurationName);
        runtimeClasspathConfiguration.deprecateForDeclaration(implementationConfigurationName,
                compileOnlyConfigurationName, runtimeOnlyConfigurationName);
    }

    private void configureCompileDefaults(final BuildProject project,
                                          final DefaultJavaPluginExtension javaExtension) {
        project.getTasks().withType(AbstractCompile.class).configureEach(compile -> {
            ConventionMapping conventionMapping = compile.getConventionMapping();
            conventionMapping.map("sourceCompatibility",
                    determineCompatibility(compile, javaExtension,
                            javaExtension::getSourceCompatibility,
                            javaExtension::getRawSourceCompatibility));
            conventionMapping.map("targetCompatibility",
                    determineCompatibility(compile, javaExtension,
                            javaExtension::getTargetCompatibility,
                            javaExtension::getRawTargetCompatibility));
        });
    }

    private Callable<String> determineCompatibility(AbstractCompile compile,
                                                    JavaPluginExtension javaExtension,
                                                    Supplier<JavaVersion> javaVersionSupplier,
                                                    Supplier<JavaVersion> rawJavaVersionSupplier) {
        return () -> {
            if (compile instanceof JavaCompile) {
                JavaCompile javaCompile = (JavaCompile) compile;
                if (javaCompile.getOptions().getRelease().isPresent()) {
                    // Release set on the task wins, no need to check *Compat has having both is
                    // illegal anyway
                    return JavaVersion.toVersion(javaCompile.getOptions().getRelease().get())
                            .toString();
                } else if (javaCompile.getJavaCompiler().isPresent()) {
                    // Toolchains in use
                    checkToolchainAndCompatibilityUsage(javaExtension, rawJavaVersionSupplier);
                    return javaCompile.getJavaCompiler().get().getMetadata().getLanguageVersion()
                            .toString();
                }
            }
//            if (compile instanceof GroovyCompile) {
//                GroovyCompile groovyCompile = (GroovyCompile) compile;
//                if (groovyCompile.getJavaLauncher().isPresent()) {
//                    checkToolchainAndCompatibilityUsage(javaExtension, rawJavaVersionSupplier);
//                    return groovyCompile.getJavaLauncher().get().getMetadata()
//                    .getLanguageVersion()
//                            .toString();
//                }
//            }
            return javaVersionSupplier.get().toString();
        };
    }

    private void checkToolchainAndCompatibilityUsage(JavaPluginExtension javaExtension,
                                                     Supplier<JavaVersion> rawJavaVersionSupplier) {
        if (((ToolchainSpecInternal) javaExtension.getToolchain()).isConfigured() &&
            rawJavaVersionSupplier.get() != null) {
            throw new InvalidUserDataException(
                    "The new Java toolchain feature cannot be used at the project level in " +
                    "combination with source and/or target compatibility");
        }
    }

    private void configureJavaDoc(final BuildProject project,
                                  final JavaPluginExtension javaPluginExtension) {
//        project.getTasks().withType(Javadoc.class).configureEach(javadoc -> {
//            javadoc.getConventionMapping().map("destinationDir",
//                    () -> new File(javaPluginExtension.getDocsDir().get().getAsFile(),
//                    "javadoc"));
//            javadoc.getConventionMapping().map("title",
//                    () -> project.getExtensions().getByType(ReportingExtension.class)
//                            .getApiDocTitle());
//            javadoc.getJavadocTool()
//                    .convention(getToolchainTool(project, JavaToolchainService::javadocToolFor));
//        });
    }

    private void configureBuildNeeded(BuildProject project) {
        project.getTasks().register(BUILD_NEEDED_TASK_NAME, buildTask -> {
            buildTask.setDescription(
                    "Assembles and tests this project and all projects it depends on.");
            buildTask.setGroup(BasePlugin.BUILD_GROUP);
            buildTask.dependsOn(BUILD_TASK_NAME);
        });
    }

    private void configureBuildDependents(BuildProject project) {
        project.getTasks().register(BUILD_DEPENDENTS_TASK_NAME, buildTask -> {
            buildTask.setDescription(
                    "Assembles and tests this project and all projects that depend on it.");
            buildTask.setGroup(BasePlugin.BUILD_GROUP);
            buildTask.dependsOn(BUILD_TASK_NAME);
        });
    }

    private void configureTest(final BuildProject project,
                               final JavaPluginExtension javaPluginExtension) {
        project.getTasks().withType(Test.class)
                .configureEach(test -> configureTestDefaults(test, project, javaPluginExtension));
    }

    private void configureTestDefaults(final Test test,
                                       BuildProject project,
                                       final JavaPluginExtension javaPluginExtension) {
        DirectoryReport htmlReport = test.getReports().getHtml();
        JUnitXmlReport xmlReport = test.getReports().getJunitXml();

        xmlReport.getOutputLocation()
                .convention(javaPluginExtension.getTestResultsDir().dir(test.getName()));
        htmlReport.getOutputLocation()
                .convention(javaPluginExtension.getTestReportDir().dir(test.getName()));
        test.getBinaryResultsDirectory().convention(
                javaPluginExtension.getTestResultsDir().dir(test.getName() + "/binary"));
        test.workingDir(project.getProjectDir());
        test.getJavaLauncher()
                .convention(getToolchainTool(project, JavaToolchainService::launcherFor));
    }

    private <T> Provider<T> getToolchainTool(BuildProject project,
                                             BiFunction<JavaToolchainService, JavaToolchainSpec,
                                                     Provider<T>> toolMapper) {
        final JavaPluginExtension extension =
                project.getExtensions().getByType(JavaPluginExtension.class);
        final JavaToolchainService service =
                project.getExtensions().getByType(JavaToolchainService.class);
        return toolMapper.apply(service, extension.getToolchain());
    }

    @Deprecated
    @Inject
    protected JavaToolchainQueryService getJavaToolchainQueryService() {
        throw new UnsupportedOperationException();
    }

}
