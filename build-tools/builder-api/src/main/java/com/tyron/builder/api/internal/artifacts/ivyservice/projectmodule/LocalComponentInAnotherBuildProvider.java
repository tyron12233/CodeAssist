package com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule;

import com.tyron.builder.api.ProjectState;
import com.tyron.builder.api.internal.project.ProjectStateUnk;
import com.tyron.builder.internal.component.local.model.LocalComponentMetadata;

import javax.annotation.concurrent.ThreadSafe;

/**
 * A provider of dependency resolution metadata for components produced by another build in the build tree.
 *
 * <p>In general, you should be using {@link LocalComponentRegistry} instead of this type.</p>
 */
@ThreadSafe
public interface LocalComponentInAnotherBuildProvider {
    /**
     * @return The component metadata for the supplied identifier.
     */
    LocalComponentMetadata getComponent(ProjectStateUnk project);
}
