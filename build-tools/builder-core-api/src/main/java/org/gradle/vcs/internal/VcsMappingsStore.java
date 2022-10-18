package org.gradle.vcs.internal;

import org.gradle.api.Action;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.invocation.Gradle;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.vcs.VcsMapping;
import org.gradle.vcs.VersionControlSpec;

import javax.annotation.Nullable;

@ServiceScope(Scopes.BuildTree.class)
public interface VcsMappingsStore {
    VcsResolver asResolver();

    void addRule(Action<? super VcsMapping> rule, Gradle gradle);

    VcsResolver NO_OP = new VcsResolver() {
        @Nullable
        @Override
        public VersionControlSpec locateVcsFor(ModuleComponentSelector selector) {
            return null;
        }

        @Override
        public boolean hasRules() {
            return false;
        }
    };
}
