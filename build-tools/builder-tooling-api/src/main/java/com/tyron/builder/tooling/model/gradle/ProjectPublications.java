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

/**
 * A model providing information about the publications of a Gradle project.
 *
 * @since 1.12
 */
public interface ProjectPublications extends Model, ProjectModel {

    /**
     * Returns the identifier for the Gradle project that these publications originate from.
     *
     * @since 2.13
     */
    @Override
    ProjectIdentifier getProjectIdentifier();

    /**
     * Returns the publications for this project.
     */
    DomainObjectSet<? extends GradlePublication> getPublications();
}
