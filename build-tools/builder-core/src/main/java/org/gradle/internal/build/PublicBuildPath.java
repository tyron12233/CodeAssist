package org.gradle.internal.build;


import org.gradle.api.internal.GradleInternal;
import org.gradle.util.Path;

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