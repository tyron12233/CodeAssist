package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.ProjectState;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.initialization.DefaultProjectDescriptor;
import com.tyron.builder.api.internal.service.scopes.Scopes;
import com.tyron.builder.api.internal.service.scopes.ServiceScope;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.internal.build.BuildProjectRegistry;
import com.tyron.builder.internal.build.BuildState;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;

/**
 * A registry of all projects present in a build tree.
 */
@ThreadSafe
@ServiceScope(Scopes.BuildTree.class)
public interface ProjectStateRegistry {
    /**
     * Returns all projects in the build tree.
     */
    Collection<? extends ProjectStateUnk> getAllProjects();

    /**
     * Locates the state object that owns the given public project model. Can use {@link ProjectInternal#getOwner()} instead.
     */
    ProjectStateUnk stateFor(BuildProject project) throws IllegalArgumentException;

    /**
     * Locates the state object that owns the project with the given identifier.
     */
    ProjectStateUnk stateFor(ProjectComponentIdentifier identifier) throws IllegalArgumentException;

    /**
     * Locates the state objects for all projects of the given build.
     */
    BuildProjectRegistry projectsFor(BuildIdentifier buildIdentifier) throws IllegalArgumentException;

    /**
     * Registers the projects of a build.
     */
    void registerProjects(BuildState build, ProjectRegistry<DefaultProjectDescriptor> projectRegistry);

    /**
     * Registers a single project.
     */
    ProjectStateUnk registerProject(BuildState owner, DefaultProjectDescriptor projectDescriptor);

    /**
     * Allows the given code to access the mutable state of any project in the tree, regardless of which other threads may be accessing the project.
     *
     * DO NOT USE THIS METHOD. It is here to allow some very specific backwards compatibility.
     */
    <T> T allowUncontrolledAccessToAnyProject(Factory<T> factory);
}