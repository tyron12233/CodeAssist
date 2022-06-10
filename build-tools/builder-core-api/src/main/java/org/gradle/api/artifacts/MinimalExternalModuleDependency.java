package org.gradle.api.artifacts;

import org.gradle.api.Incubating;
import org.gradle.internal.HasInternalProtocol;

/**
 * The minimal information Gradle needs to address an external module.
 *
 * @since 6.8
 */
@Incubating
@HasInternalProtocol
public interface MinimalExternalModuleDependency {
    ModuleIdentifier getModule();
    VersionConstraint getVersionConstraint();
}
