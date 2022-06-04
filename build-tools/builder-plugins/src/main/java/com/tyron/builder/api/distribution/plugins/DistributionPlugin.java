/*
 * Copyright 2012 the original author or authors.
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

package com.tyron.builder.api.distribution.plugins;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.DefaultTask;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.artifacts.PublishArtifact;
import com.tyron.builder.api.distribution.Distribution;
import com.tyron.builder.api.distribution.DistributionContainer;
import com.tyron.builder.api.distribution.internal.DefaultDistributionContainer;
import com.tyron.builder.api.file.CopySpec;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.artifacts.dsl.LazyPublishArtifact;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.plugins.DefaultArtifactPublicationSet;
import com.tyron.builder.api.plugins.BasePlugin;
import com.tyron.builder.api.tasks.Sync;
import com.tyron.builder.api.tasks.TaskProvider;
import com.tyron.builder.api.tasks.bundling.AbstractArchiveTask;
import com.tyron.builder.api.tasks.bundling.Tar;
import com.tyron.builder.api.tasks.bundling.Zip;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.util.internal.TextUtil;

import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.Callable;

import javax.inject.Inject;

/**
 * <p>A {@link Plugin} to package project as a distribution.</p>
 *
 * @see <a href="https://docs.gradle.org/current/userguide/distribution_plugin.html">Distribution plugin reference</a>
 */
public class DistributionPlugin implements Plugin<BuildProject> {
    /**
     * Name of the main distribution
     */
    public static final String MAIN_DISTRIBUTION_NAME = "main";
    public static final String TASK_INSTALL_NAME = "installDist";

    private static final String DISTRIBUTION_GROUP = "distribution";
    private static final String TASK_DIST_ZIP_NAME = "distZip";
    private static final String TASK_DIST_TAR_NAME = "distTar";
    private static final String TASK_ASSEMBLE_NAME = "assembleDist";

    private final Instantiator instantiator;
    private final FileOperations fileOperations;
    private final CollectionCallbackActionDecorator callbackActionDecorator;

    @Inject
    public DistributionPlugin(Instantiator instantiator, FileOperations fileOperations, CollectionCallbackActionDecorator callbackActionDecorator) {
        this.instantiator = instantiator;
        this.fileOperations = fileOperations;
        this.callbackActionDecorator = callbackActionDecorator;
    }

    @Override
    public void apply(final BuildProject project) {
        project.getPluginManager().apply(BasePlugin.class);
        DistributionContainer distributions = project.getExtensions().create(DistributionContainer.class, "distributions", DefaultDistributionContainer.class, Distribution.class, instantiator, project.getObjects(), fileOperations, callbackActionDecorator);

        // TODO - refactor this action out so it can be unit tested
        distributions.all(dist -> {
            dist.getContents().from("src/" + dist.getName() + "/dist");
            final String zipTaskName;
            final String tarTaskName;
            final String installTaskName;
            final String assembleTaskName;
            if (dist.getName().equals(MAIN_DISTRIBUTION_NAME)) {
                zipTaskName = TASK_DIST_ZIP_NAME;
                tarTaskName = TASK_DIST_TAR_NAME;
                installTaskName = TASK_INSTALL_NAME;
                assembleTaskName = TASK_ASSEMBLE_NAME;
                dist.getDistributionBaseName().convention(project.getName());
            } else {
                zipTaskName = dist.getName() + "DistZip";
                tarTaskName = dist.getName() + "DistTar";
                installTaskName = "install" + StringUtils.capitalize(dist.getName()) + "Dist";
                assembleTaskName = "assemble" + StringUtils.capitalize(dist.getName()) + "Dist";
                dist.getDistributionBaseName().convention(String.format("%s-%s", project.getName(), dist.getName()));
            }

            addArchiveTask(project, zipTaskName, Zip.class, dist);
            addArchiveTask(project, tarTaskName, Tar.class, dist);
            addInstallTask(project, installTaskName, dist);
            addAssembleTask(project, assembleTaskName, dist, zipTaskName, tarTaskName);
        });
        distributions.create(MAIN_DISTRIBUTION_NAME);

        // TODO: Maintain old behavior of checking for empty-string distribution base names.
        // It would be nice if we could do this as validation on the property itself.
        project.afterEvaluate(p -> {
            distributions.forEach(distribution -> {
                if (distribution.getDistributionBaseName().get().equals("")) {
                    throw new BuildException(String.format("Distribution '%s' must not have an empty distributionBaseName.", distribution.getName()));
                }
            });
        });
    }

    private <T extends AbstractArchiveTask> void addArchiveTask(final BuildProject project, String taskName, Class<T> type, final Distribution distribution) {
        final TaskProvider<T> archiveTask = project.getTasks().register(taskName, type, task -> {
            task.setDescription("Bundles the project as a distribution.");
            task.setGroup(DISTRIBUTION_GROUP);
            task.getArchiveBaseName().convention(distribution.getDistributionBaseName());

            final CopySpec childSpec = project.copySpec();
            childSpec.with(distribution.getContents());
            childSpec.into((Callable<String>)() -> TextUtil.minus(task.getArchiveFileName().get(), "." + task.getArchiveExtension().get()));
            task.with(childSpec);
        });

        PublishArtifact archiveArtifact = new LazyPublishArtifact(archiveTask);
        project.getExtensions().getByType(DefaultArtifactPublicationSet.class).addCandidate(archiveArtifact);
    }

    private void addInstallTask(final BuildProject project, final String taskName, final Distribution distribution) {
        project.getTasks().register(taskName, Sync.class, installTask -> {
            installTask.setDescription("Installs the project as a distribution as-is.");
            installTask.setGroup(DISTRIBUTION_GROUP);
            installTask.with(distribution.getContents());
            installTask.into(project.getLayout().getBuildDirectory().dir(distribution.getDistributionBaseName().map(baseName -> "install/" + baseName)));
        });
    }

    private void addAssembleTask(BuildProject project, final String taskName, final Distribution distribution, final String... tasks) {
        project.getTasks().register(taskName, DefaultTask.class, assembleTask -> {
            assembleTask.setDescription("Assembles the " + distribution.getName() + " distributions");
            assembleTask.setGroup(DISTRIBUTION_GROUP);
            assembleTask.dependsOn((Object[])tasks);
        });
    }
}
