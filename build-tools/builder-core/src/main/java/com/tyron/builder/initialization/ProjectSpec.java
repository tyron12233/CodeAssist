package com.tyron.builder.initialization;

import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.internal.project.ProjectIdentifier;
import com.tyron.builder.api.internal.project.ProjectRegistry;

public interface ProjectSpec {
    /**
     * Determines whether the given registry contains at least 1 project which meets this spec.
     */
    boolean containsProject(ProjectRegistry<? extends ProjectIdentifier> registry);

    /**
     * Returns the single project in the given registry which meets this spec.
     * @return the project.
     * @throws InvalidUserDataException When project cannot be selected due to some user input mismatch, or when there are no matching projects
     * or multiple matching projects.
     */
    <T extends ProjectIdentifier> T selectProject(String settingsDescription, ProjectRegistry<? extends T> registry) throws InvalidUserDataException;
}
