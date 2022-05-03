package com.tyron.builder.api.internal.artifacts.component;

import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentSelector;
import com.tyron.builder.api.internal.artifacts.Module;

public interface ComponentIdentifierFactory {
    ComponentIdentifier createComponentIdentifier(Module module);

    ProjectComponentSelector createProjectComponentSelector(String projectPath);

    ProjectComponentIdentifier createProjectComponentIdentifier(ProjectComponentSelector selector);
}
