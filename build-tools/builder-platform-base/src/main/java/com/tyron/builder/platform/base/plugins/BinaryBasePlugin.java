/*
 * Copyright 2016 the original author or authors.
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
package com.tyron.builder.platform.base.plugins;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.DefaultTask;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.language.base.plugins.LifecycleBasePlugin;
import com.tyron.builder.model.Each;
import com.tyron.builder.model.Finalize;
import com.tyron.builder.model.Model;
import com.tyron.builder.model.Mutate;
import com.tyron.builder.model.RuleSource;
import com.tyron.builder.model.internal.core.NamedEntityInstantiator;
import com.tyron.builder.platform.base.BinaryContainer;
import com.tyron.builder.platform.base.BinarySpec;
import com.tyron.builder.platform.base.ComponentType;
import com.tyron.builder.platform.base.TypeBuilder;
import com.tyron.builder.platform.base.binary.BaseBinarySpec;
import com.tyron.builder.platform.base.internal.BinarySpecInternal;

/**
 * Base plugin for binaries support.
 * <p>
 * - Adds a {@link BinarySpec} container named {@code binaries} to the project.
 * - Registers the base {@link BinarySpec} type.
 * - For each {@link BinarySpec}, registers a lifecycle task to assemble that binary.
 * - For each {@link BinarySpec}, adds the binary's source sets as its default inputs.
 * - Links the tasks for each {@link BinarySpec} across to the tasks container.
 */
@Incubating
public class BinaryBasePlugin implements Plugin<BuildProject> {

    @Override
    public void apply(final BuildProject target) {
        target.getPluginManager().apply(ComponentBasePlugin.class);
    }

    @SuppressWarnings("UnusedDeclaration")
    static class Rules extends RuleSource {
        @Model
        void binaries(BinaryContainer binaries) {
        }

        @ComponentType
        void registerBaseBinarySpec(TypeBuilder<BinarySpec> builder) {
            builder.defaultImplementation(BaseBinarySpec.class);
            builder.internalView(BinarySpecInternal.class);
        }

        @Mutate
        void copyBinaryTasksToTaskContainer(TaskContainer tasks, BinaryContainer binaries) {
            for (BinarySpecInternal binary : binaries.withType(BinarySpecInternal.class)) {
                TaskContainerInternal tasksInternal = (TaskContainerInternal) tasks;
                if (binary.isLegacyBinary()) {
                    continue;
                }
                tasksInternal.addAllInternal(binary.getTasks());
                Task buildTask = binary.getBuildTask();
                if (buildTask != null) {
                    tasksInternal.addInternal(buildTask);
                }
            }
        }

        @Finalize
        public void defineBuildLifecycleTask(@Each BinarySpecInternal binary,
                                             NamedEntityInstantiator<Task> taskInstantiator) {
            if (binary.isLegacyBinary()) {
                return;
            }
            TaskInternal binaryLifecycleTask =
                    taskInstantiator.create(binary.getProjectScopedName(), DefaultTask.class);
            binaryLifecycleTask.setGroup(LifecycleBasePlugin.BUILD_GROUP);
            binaryLifecycleTask.setDescription(String.format("Assembles %s.", binary));
            binary.setBuildTask(binaryLifecycleTask);
        }

        @Finalize
        void addSourceSetsOwnedByBinariesToTheirInputs(@Each BinarySpecInternal binary) {
            if (binary.isLegacyBinary()) {
                return;
            }
            binary.getInputs().addAll(binary.getSources().values());
        }
    }
}
