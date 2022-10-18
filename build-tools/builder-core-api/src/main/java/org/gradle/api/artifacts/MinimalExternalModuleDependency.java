package org.gradle.api.artifacts;

import org.gradle.internal.HasInternalProtocol;

/**
 * The minimal information Gradle needs to address an external module.
 *
 * @since 6.8
 */
@HasInternalProtocol
public interface MinimalExternalModuleDependency extends ExternalModuleDependency {
    ModuleIdentifier getModule();
    VersionConstraint getVersionConstraint();
}
