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

import static com.tyron.builder.api.distribution.plugins.DistributionPlugin.TASK_INSTALL_NAME;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.api.distribution.Distribution;
import com.tyron.builder.api.distribution.DistributionContainer;
import com.tyron.builder.api.distribution.plugins.DistributionPlugin;
import com.tyron.builder.api.file.CopySpec;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.plugins.internal.DefaultApplicationPluginConvention;
import com.tyron.builder.api.plugins.internal.DefaultJavaApplication;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.provider.ProviderFactory;
import com.tyron.builder.api.tasks.JavaExec;
import com.tyron.builder.api.tasks.SourceSet;
import com.tyron.builder.api.tasks.Sync;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.tasks.TaskProvider;
import com.tyron.builder.api.tasks.application.CreateStartScripts;
import com.tyron.builder.api.tasks.compile.JavaCompile;
import com.tyron.builder.jvm.toolchain.JavaToolchainService;
import com.tyron.builder.jvm.toolchain.JavaToolchainSpec;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;

/**
 * <p>A {@link Plugin} which runs a project as a Java Application.</p>
 *
 * <p>The plugin can be configured via its companion {@link ApplicationPluginConvention} object.</p>
 *
 * @see
 * <a href="https://docs.gradle.org/current/userguide/application_plugin.html">Application plugin reference</a>
 */
public class ApplicationPlugin implements Plugin<BuildProject> {
    public static final String APPLICATION_PLUGIN_NAME = "application";
    public static final String APPLICATION_GROUP = APPLICATION_PLUGIN_NAME;
    public static final String TASK_RUN_NAME = "run";
    public static final String TASK_START_SCRIPTS_NAME = "startScripts";
    public static final String TASK_DIST_ZIP_NAME = "distZip";
    public static final String TASK_DIST_TAR_NAME = "distTar";

    @Override
    public void apply(final BuildProject project) {
        TaskContainer tasks = project.getTasks();

        project.getPluginManager().apply(JavaPlugin.class);
        project.getPluginManager().apply(DistributionPlugin.class);

        ApplicationPluginConvention pluginConvention = addConvention(project);
        JavaApplication pluginExtension = addExtensions(project, pluginConvention);
        addRunTask(project, pluginExtension, pluginConvention);
        addCreateScriptsTask(project, pluginExtension, pluginConvention);
        configureJavaCompileTask(tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile.class),
                pluginExtension);
        configureInstallTask(project.getProviders(), tasks.named(TASK_INSTALL_NAME, Sync.class),
                pluginConvention);

        DistributionContainer distributions =
                (DistributionContainer) project.getExtensions().getByName("distributions");
        Distribution mainDistribution =
                distributions.getByName(DistributionPlugin.MAIN_DISTRIBUTION_NAME);
        configureDistribution(project, mainDistribution, pluginConvention);
    }

    private void configureJavaCompileTask(TaskProvider<JavaCompile> javaCompile,
                                          JavaApplication pluginExtension) {
        javaCompile.configure(j -> j.getOptions().getJavaModuleMainClass()
                .convention(pluginExtension.getMainClass()));
    }

    private void configureInstallTask(ProviderFactory providers,
                                      TaskProvider<Sync> installTask,
                                      ApplicationPluginConvention pluginConvention) {
        installTask.configure(task -> task.doFirst("don't overwrite existing directories",
                new PreventDestinationOverwrite(
                        providers.provider(pluginConvention::getApplicationName),
                        providers.provider(pluginConvention::getExecutableDir))));
    }

    private static class PreventDestinationOverwrite implements Action<Task> {
        private final Provider<String> applicationName;
        private final Provider<String> executableDir;

        private PreventDestinationOverwrite(Provider<String> applicationName,
                                            Provider<String> executableDir) {
            this.applicationName = applicationName;
            this.executableDir = executableDir;
        }


        @Override
        public void execute(Task task) {
            Sync sync = (Sync) task;
            File destinationDir = sync.getDestinationDir();
            if (destinationDir.isDirectory()) {
                String[] children = destinationDir.list();
                if (children == null) {
                    throw new UncheckedIOException("Could not list directory " + destinationDir);
                }
                if (children.length > 0) {
                    if (!new File(destinationDir, "lib").isDirectory() ||
                        !new File(destinationDir, executableDir.get()).isDirectory()) {
                        throw new BuildException("The specified installation directory \'" +
                                                 destinationDir +
                                                 "\' is neither empty nor does it contain an " +
                                                 "installation for \'" +
                                                 applicationName.get() +
                                                 "\'.\n" +
                                                 "If you really want to install to this " +
                                                 "directory, delete it and run the install task " +
                                                 "again.\n" +
                                                 "Alternatively, choose a different installation" +
                                                 " directory.");
                    }
                }
            }
        }
    }

    private ApplicationPluginConvention addConvention(BuildProject project) {
        ApplicationPluginConvention pluginConvention =
                new DefaultApplicationPluginConvention(project);
        pluginConvention.setApplicationName(project.getName());
        project.getConvention().getPlugins().put("application", pluginConvention);
        return pluginConvention;
    }

    private JavaApplication addExtensions(BuildProject project,
                                          ApplicationPluginConvention pluginConvention) {
        return project.getExtensions()
                .create(JavaApplication.class, "application", DefaultJavaApplication.class,
                        pluginConvention);
    }

    private void addRunTask(BuildProject project,
                            JavaApplication pluginExtension,
                            ApplicationPluginConvention pluginConvention) {
        project.getTasks().register(TASK_RUN_NAME, JavaExec.class, run -> {
            run.setDescription("Runs this project as a JVM application");
            run.setGroup(APPLICATION_GROUP);

            FileCollection runtimeClasspath =
                    project.files().from((Callable<FileCollection>) () -> {
                        if (run.getMainModule().isPresent()) {
                            return jarsOnlyRuntimeClasspath(project);
                        } else {
                            return runtimeClasspath(project);
                        }
                    });
            run.setClasspath(runtimeClasspath);
            run.getMainModule().set(pluginExtension.getMainModule());
            run.getMainClass().set(pluginExtension.getMainClass());
            run.getConventionMapping()
                    .map("jvmArgs", pluginConvention::getApplicationDefaultJvmArgs);

            JavaPluginExtension javaPluginExtension =
                    project.getExtensions().getByType(JavaPluginExtension.class);
            run.getModularity().getInferModulePath()
                    .convention(javaPluginExtension.getModularity().getInferModulePath());
            run.getJavaLauncher()
                    .convention(getToolchainTool(project, JavaToolchainService::launcherFor));
        });
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

    // @Todo: refactor this task configuration to extend a copy task and use replace tokens
    private void addCreateScriptsTask(BuildProject project,
                                      JavaApplication pluginExtension,
                                      ApplicationPluginConvention pluginConvention) {
        project.getTasks()
                .register(TASK_START_SCRIPTS_NAME, CreateStartScripts.class, startScripts -> {
                    startScripts.setDescription(
                            "Creates OS specific scripts to run the project as a JVM application.");
                    startScripts.setClasspath(jarsOnlyRuntimeClasspath(project));

                    startScripts.getMainModule().set(pluginExtension.getMainModule());
                    startScripts.getMainClass().set(pluginExtension.getMainClass());

                    startScripts.getConventionMapping()
                            .map("applicationName", pluginConvention::getApplicationName);

                    startScripts.getConventionMapping()
                            .map("outputDir", () -> new File(project.getBuildDir(), "scripts"));

                    startScripts.getConventionMapping()
                            .map("executableDir", pluginConvention::getExecutableDir);

                    startScripts.getConventionMapping()
                            .map("defaultJvmOpts", pluginConvention::getApplicationDefaultJvmArgs);

                    JavaPluginExtension javaPluginExtension =
                            project.getExtensions().getByType(JavaPluginExtension.class);
                    startScripts.getModularity().getInferModulePath()
                            .convention(javaPluginExtension.getModularity().getInferModulePath());
                });
    }

    private FileCollection runtimeClasspath(BuildProject project) {

        return project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME).getRuntimeClasspath();
    }

    private FileCollection jarsOnlyRuntimeClasspath(BuildProject project) {
        return project.getTasks().getAt(JavaPlugin.JAR_TASK_NAME).getOutputs().getFiles()
                .plus(project.getConfigurations()
                        .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
    }

    private CopySpec configureDistribution(BuildProject project,
                                           Distribution mainDistribution,
                                           ApplicationPluginConvention pluginConvention) {
        mainDistribution.getDistributionBaseName()
                .convention(project.provider(pluginConvention::getApplicationName));
        CopySpec distSpec = mainDistribution.getContents();

        TaskProvider<Task> jar = project.getTasks().named(JavaPlugin.JAR_TASK_NAME);
        TaskProvider<Task> startScripts = project.getTasks().named(TASK_START_SCRIPTS_NAME);

        CopySpec libChildSpec = project.copySpec();
        libChildSpec.into("lib");
        libChildSpec.from(jar);
        libChildSpec.from(project.getConfigurations()
                .named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));

        CopySpec binChildSpec = project.copySpec();

        binChildSpec.into((Callable<Object>) pluginConvention::getExecutableDir);
        binChildSpec.from(startScripts);
        binChildSpec.setFileMode(0755);

        CopySpec childSpec = project.copySpec();
        childSpec.from(project.file("src/dist"));
        childSpec.with(libChildSpec);
        childSpec.with(binChildSpec);

        distSpec.with(childSpec);

        distSpec.with(pluginConvention.getApplicationDistribution());
        return distSpec;
    }
}
