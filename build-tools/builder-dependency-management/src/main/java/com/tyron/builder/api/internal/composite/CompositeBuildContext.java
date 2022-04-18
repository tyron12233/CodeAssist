package com.tyron.builder.api.internal.composite;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.DependencySubstitution;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.internal.Pair;

import java.util.Set;

public interface CompositeBuildContext {// extends DependencySubstitutionRules {
    void addAvailableModules(Set<Pair<ModuleVersionIdentifier, ProjectComponentIdentifier>> availableModules);
    void registerSubstitution(Action<DependencySubstitution> substitutions);
}
