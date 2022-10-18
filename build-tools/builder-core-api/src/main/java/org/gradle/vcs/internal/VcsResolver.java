package org.gradle.vcs.internal;

import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.vcs.VersionControlSpec;

import javax.annotation.Nullable;

public interface VcsResolver {
    /**
     * Returns the VCS to use to search for matches to the given selector, or null if no such VCS.
     */
    @Nullable
    VersionControlSpec locateVcsFor(ModuleComponentSelector selector);

    /**
     * Does this resolver do anything?
     */
    boolean hasRules();
}
