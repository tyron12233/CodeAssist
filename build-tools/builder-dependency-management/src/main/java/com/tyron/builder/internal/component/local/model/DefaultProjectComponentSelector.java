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
package com.tyron.builder.internal.component.local.model;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentSelector;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.artifacts.DefaultProjectComponentIdentifier;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.util.Path;

import java.util.Collections;
import java.util.List;

public class DefaultProjectComponentSelector implements ProjectComponentSelector {
    private final BuildIdentifier buildIdentifier;
    private final Path projectPath;
    private final Path identityPath;
    private final String projectName;
    private final ImmutableAttributes attributes;
    private final List<Capability> requestedCapabilities;
    private String displayName;

    public DefaultProjectComponentSelector(BuildIdentifier buildIdentifier, Path identityPath, Path projectPath, String projectName, ImmutableAttributes attributes, List<Capability> requestedCapabilities) {
        assert buildIdentifier != null : "build cannot be null";
        assert identityPath != null : "identity path cannot be null";
        assert projectPath != null : "project path cannot be null";
        assert projectName != null : "project name cannot be null";
        assert attributes != null : "attributes cannot be null";
        assert requestedCapabilities != null : "capabilities cannot be null";
        this.buildIdentifier = buildIdentifier;
        this.identityPath = identityPath;
        this.projectPath = projectPath;
        this.projectName = projectName;
        this.attributes = attributes;
        this.requestedCapabilities = requestedCapabilities;
    }

    @Override
    public String getDisplayName() {
        if (displayName == null) {
            displayName = "project " + identityPath;
        }
        return displayName;
    }

    public BuildIdentifier getBuildIdentifier() {
        return buildIdentifier;
    }

    @Override
    public String getBuildName() {
        return buildIdentifier.getName();
    }

    public Path getIdentityPath() {
        return identityPath;
    }

    @Override
    public String getProjectPath() {
        return projectPath.getPath();
    }

    public Path projectPath() {
        return projectPath;
    }

    public String getProjectName() {
        return projectName;
    }

    @Override
    public boolean matchesStrictly(ComponentIdentifier identifier) {
        assert identifier != null : "identifier cannot be null";

        if (identifier instanceof DefaultProjectComponentIdentifier) {
            DefaultProjectComponentIdentifier projectComponentIdentifier = (DefaultProjectComponentIdentifier) identifier;
            return projectComponentIdentifier.getIdentityPath().equals(identityPath);
        }

        return false;
    }

    @Override
    public AttributeContainer getAttributes() {
        return attributes;
    }

    @Override
    public List<Capability> getRequestedCapabilities() {
        return requestedCapabilities;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultProjectComponentSelector)) {
            return false;
        }
        DefaultProjectComponentSelector that = (DefaultProjectComponentSelector) o;
        if (!identityPath.equals(that.identityPath)) {
            return false;
        }
        if (!attributes.equals(that.attributes)) {
            return false;
        }
        return requestedCapabilities.equals(that.requestedCapabilities);
    }

    @Override
    public int hashCode() {
        return identityPath.hashCode();
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    public static ProjectComponentSelector newSelector(BuildProject project) {
        return newSelector(project, ImmutableAttributes.EMPTY, Collections.emptyList());
    }

    public static ProjectComponentSelector newSelector(BuildProject project, ImmutableAttributes attributes, List<Capability> requestedCapabilities) {
        ProjectInternal projectInternal = (ProjectInternal) project;
        ProjectComponentIdentifier projectComponentIdentifier = projectInternal.getOwner().getComponentIdentifier();
        return new DefaultProjectComponentSelector(projectComponentIdentifier.getBuild(), projectInternal.getIdentityPath(), projectInternal.getProjectPath(), project.getName(), attributes, requestedCapabilities);
    }

    public static ProjectComponentSelector newSelector(ProjectComponentIdentifier identifier) {
        DefaultProjectComponentIdentifier projectComponentIdentifier = (DefaultProjectComponentIdentifier) identifier;
        return new DefaultProjectComponentSelector(projectComponentIdentifier.getBuild(), projectComponentIdentifier.getIdentityPath(), projectComponentIdentifier.projectPath(), projectComponentIdentifier.getProjectName(), ImmutableAttributes.EMPTY, Collections.emptyList());
    }

    public static ProjectComponentSelector newSelector(ProjectComponentIdentifier identifier, ImmutableAttributes attributes, List<Capability> requestedCapabilities) {
        DefaultProjectComponentIdentifier projectComponentIdentifier = (DefaultProjectComponentIdentifier) identifier;
        return new DefaultProjectComponentSelector(projectComponentIdentifier.getBuild(), projectComponentIdentifier.getIdentityPath(), projectComponentIdentifier.projectPath(), projectComponentIdentifier.getProjectName(), attributes, requestedCapabilities);
    }

    public static ProjectComponentSelector withAttributes(ProjectComponentSelector selector, ImmutableAttributes attributes) {
        DefaultProjectComponentSelector current = (DefaultProjectComponentSelector) selector;
        return new DefaultProjectComponentSelector(
            current.buildIdentifier,
            current.identityPath,
            current.projectPath,
            current.projectName,
            attributes,
            current.requestedCapabilities
        );
    }

    public static ComponentSelector withCapabilities(ProjectComponentSelector selector, List<Capability> requestedCapabilities) {
        DefaultProjectComponentSelector current = (DefaultProjectComponentSelector) selector;
        return new DefaultProjectComponentSelector(
            current.buildIdentifier,
            current.identityPath,
            current.projectPath,
            current.projectName,
            current.attributes,
            requestedCapabilities
        );
    }

    public ProjectComponentIdentifier toIdentifier() {
        return new DefaultProjectComponentIdentifier(buildIdentifier, identityPath, projectPath, projectName);
    }
}
