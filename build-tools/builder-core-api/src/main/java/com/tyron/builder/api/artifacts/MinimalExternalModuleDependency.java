package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.internal.HasInternalProtocol;

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
