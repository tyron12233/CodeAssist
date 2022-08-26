package org.gradle.plugins.ide.internal;

import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.tasks.TaskDependencyContainer;

import javax.annotation.Nullable;
import java.util.List;

/**
 * This should merge into {@link org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectPublicationRegistry}.
 */
public interface IdeArtifactRegistry {
    /**
     * Registers an IDE project model to be included in the IDE workspace.
     */
    void registerIdeProject(IdeProjectMetadata ideProjectMetadata);

    /**
     * Finds an IDE project with the given type in the given project. Does not execute tasks to build the project file.
     */
    @Nullable
    <T extends IdeProjectMetadata> T getIdeProject(Class<T> type, ProjectComponentIdentifier project);

    /**
     * Finds all known IDE projects with the given type that should be included in the IDE workspace. Does not execute tasks to build the artifact.
     */
    <T extends IdeProjectMetadata> List<Reference<T>> getIdeProjects(Class<T> type);

    /**
     * Returns a {@link FileCollection} containing the files for all IDE projects with the specified type that should be included in the IDE workspace.
     */
    FileCollection getIdeProjectFiles(Class<? extends IdeProjectMetadata> type);

    interface Reference<T extends IdeProjectMetadata> extends TaskDependencyContainer {
        T get();

        ProjectComponentIdentifier getOwningProject();
    }
}