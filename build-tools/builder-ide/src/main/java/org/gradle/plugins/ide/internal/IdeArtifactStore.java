package org.gradle.plugins.ide.internal;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;

/**
 * This is separate from {@link DefaultIdeArtifactRegistry} so that the data can be shared across a build tree, while the {@link DefaultIdeArtifactRegistry} is scoped to a particular consuming project.
 */
public class IdeArtifactStore {
    private final ListMultimap<ProjectComponentIdentifier, IdeProjectMetadata> metadata = ArrayListMultimap.create();

    public void put(ProjectComponentIdentifier projectId, IdeProjectMetadata ideProjectMetadata) {
        metadata.put(projectId, ideProjectMetadata);
    }

    public Iterable<? extends IdeProjectMetadata> get(ProjectComponentIdentifier project) {
        return metadata.get(project);
    }
}