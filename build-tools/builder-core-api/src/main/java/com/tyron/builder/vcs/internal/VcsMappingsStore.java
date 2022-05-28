package com.tyron.builder.vcs.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.component.ModuleComponentSelector;
import com.tyron.builder.api.invocation.Gradle;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.vcs.VcsMapping;
import com.tyron.builder.vcs.VersionControlSpec;

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
