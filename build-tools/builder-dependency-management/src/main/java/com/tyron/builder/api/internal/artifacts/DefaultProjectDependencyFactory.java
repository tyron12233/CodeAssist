/*
 * Copyright 2013 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.artifacts.ProjectDependency;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.artifacts.dependencies.DefaultProjectDependency;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.typeconversion.NotationParser;

public class DefaultProjectDependencyFactory {
    private final Instantiator instantiator;
    private final boolean buildProjectDependencies;
    private final NotationParser<Object, Capability> capabilityNotationParser;
    private final ImmutableAttributesFactory attributesFactory;

    public DefaultProjectDependencyFactory(Instantiator instantiator, boolean buildProjectDependencies, NotationParser<Object, Capability> capabilityNotationParser, ImmutableAttributesFactory attributesFactory) {
        this.instantiator = instantiator;
        this.buildProjectDependencies = buildProjectDependencies;
        this.capabilityNotationParser = capabilityNotationParser;
        this.attributesFactory = attributesFactory;
    }

    public ProjectDependency create(ProjectInternal project, String configuration) {
        DefaultProjectDependency projectDependency = instantiator.newInstance(DefaultProjectDependency.class, project, configuration, buildProjectDependencies);
        prepareProject(projectDependency);
        return projectDependency;
    }

    private void prepareProject(DefaultProjectDependency projectDependency) {
        projectDependency.setAttributesFactory(attributesFactory);
        projectDependency.setCapabilityNotationParser(capabilityNotationParser);
    }

    public ProjectDependency create(BuildProject project) {
        DefaultProjectDependency projectDependency = instantiator.newInstance(DefaultProjectDependency.class, project, buildProjectDependencies);
        prepareProject(projectDependency);
        return projectDependency;
    }
}
