package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.ModuleVersionSelector;

import java.util.function.Predicate;

public class ModuleVersionSelectorStrictSpec implements Predicate<ModuleVersionIdentifier> {

    private final ModuleVersionSelector selector;

    public ModuleVersionSelectorStrictSpec(ModuleVersionSelector selector) {
        assert selector != null;
        this.selector = selector;
    }

    @Override
    public boolean test(ModuleVersionIdentifier candidate) {
        return candidate.getName().equals(selector.getName())
                && candidate.getGroup().equals(selector.getGroup())
                && candidate.getVersion().equals(selector.getVersion());
    }
}
