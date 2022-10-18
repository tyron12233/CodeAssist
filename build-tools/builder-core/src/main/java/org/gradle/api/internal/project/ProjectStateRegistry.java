package org.gradle.api.internal.project;

import org.gradle.api.Project;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.initialization.DefaultProjectDescriptor;
import org.gradle.internal.Factory;
import org.gradle.internal.build.BuildProjectRegistry;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

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
    Collection<? extends ProjectState> getAllProjects();

    /**
     * Locates the state object that owns the given public project model. Can use {@link ProjectInternal#getOwner()} instead.
     */
    ProjectState stateFor(Project project) throws IllegalArgumentException;

    /**
     * Locates the state object that owns the project with the given identifier.
     */
    ProjectState stateFor(ProjectComponentIdentifier identifier) throws IllegalArgumentException;

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
    ProjectState registerProject(BuildState owner, DefaultProjectDescriptor projectDescriptor);

    /**
     * Allows the given code to access the mutable state of any project in the tree, regardless of which other threads may be accessing the project.
     *
     * DO NOT USE THIS METHOD. It is here to allow some very specific backwards compatibility.
     */
    <T> T allowUncontrolledAccessToAnyProject(Factory<T> factory);
}