package org.gradle.vcs;

import org.gradle.api.Action;
import org.gradle.api.artifacts.component.ComponentSelector;

/**
 * A dependency mapping provided by a VCS repository.
 *
 * @since 4.4
 */
public interface VcsMapping {
    /**
     * The requested dependency, before it is resolved.
     * The requested dependency does not change even if there are multiple dependency substitution rules
     * that manipulate the dependency metadata.
     */
    ComponentSelector getRequested();

    /**
     * Specifies the VCS location for the requested component.
     *
     * @since 4.6
     */
    void from(VersionControlSpec versionControlSpec);

    /**
     * Specifies the VCS location for the requested component.
     */
    <T extends VersionControlSpec> void from(Class<T> type, Action<? super T> configureAction);
}
