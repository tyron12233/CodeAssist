/*
 * Copyright 2014 the original author or authors.
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

package com.tyron.builder.tooling.model.gradle;

import com.tyron.builder.tooling.model.DomainObjectSet;
import com.tyron.builder.tooling.model.Model;
import com.tyron.builder.tooling.model.ProjectIdentifier;
import com.tyron.builder.tooling.model.ProjectModel;
import com.tyron.builder.tooling.model.Task;
import com.tyron.builder.tooling.model.TaskSelector;

/**
 * A model providing access to {@link com.tyron.builder.tooling.model.Launchable} instances that can be used
 * to initiate Gradle build.
 *
 * <p>To launch a build, you pass one or more {@link com.tyron.builder.tooling.model.Launchable} instances
 * to either {@link com.tyron.builder.tooling.BuildLauncher#forTasks(Iterable)} or {@link com.tyron.builder.tooling.BuildLauncher#forLaunchables(Iterable)}.</p>
 *
 * @since 1.12
 */
public interface BuildInvocations extends Model, ProjectModel {

    /**
     * Returns the identifier for the Gradle project that these invocations originate from.
     *
     * @since 2.13
     */
    @Override
    ProjectIdentifier getProjectIdentifier();

    /**
     * Returns tasks selectors that can be used to execute a build.
     *
     * Selector is a {@link com.tyron.builder.tooling.model.Launchable} that requests to build all tasks with a given name in context of some project and all its subprojects.
     * @return The task selectors.
     * @since 1.12
     */
    DomainObjectSet<? extends TaskSelector> getTaskSelectors();

    /**
     * Returns the tasks that can be used to execute a build.
     *
     * @return The tasks.
     * @since 1.12
     */
    DomainObjectSet<? extends Task> getTasks();
}
