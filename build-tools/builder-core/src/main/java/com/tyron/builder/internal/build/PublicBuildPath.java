package com.tyron.builder.internal.build;


import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.util.Path;

/**
 * A reference to public path for a build, available via the service registry.
 *
 * Usages of {@link GradleInternal#getIdentityPath()} should be migrated to this type, to avoid unnecessary penetration of GradleInternal.
 */
public interface PublicBuildPath {

    /**
     * The build's, unique, build path.
     */
    Path getBuildPath();

}