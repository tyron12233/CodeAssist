package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.internal.service.ServiceRegistry;
import com.tyron.builder.api.project.BuildProject;

public interface ProjectInternal extends BuildProject {

    /**
     * Returns the {@link ProjectState} that manages the state of this instance.
     */
    ProjectState getOwner();

    ServiceRegistry getServices();
}
