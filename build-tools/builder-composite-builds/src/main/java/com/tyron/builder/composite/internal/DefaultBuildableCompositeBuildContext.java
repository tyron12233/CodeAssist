package com.tyron.builder.composite.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.DependencySubstitution;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.internal.composite.CompositeBuildContext;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.internal.Pair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DefaultBuildableCompositeBuildContext implements CompositeBuildContext {
    // TODO: Synchronization
    private final Set<Pair<ModuleVersionIdentifier, ProjectComponentIdentifier>> availableModules = new HashSet<>();
    private final List<Action<DependencySubstitution>> substitutionRules = new ArrayList<>();

    public DefaultBuildableCompositeBuildContext() {
    }

    @Override
    public void addAvailableModules(Set<Pair<ModuleVersionIdentifier, ProjectComponentIdentifier>> availableModules) {
        this.availableModules.addAll(availableModules);
    }

    @Override
    public void registerSubstitution(Action<DependencySubstitution> substitutions) {
        substitutionRules.add(substitutions);
    }

    @Override
    public Action<DependencySubstitution> getRuleAction() {
        List<Action<DependencySubstitution>> allActions = new ArrayList<>();
        if (!availableModules.isEmpty()) {
            // Automatically substitute all available modules
            allActions.add(new CompositeBuildDependencySubstitutions(availableModules));
        }
        allActions.addAll(substitutionRules);
        return Actions.composite(allActions);
    }

    @Override
    public boolean rulesMayAddProjectDependency() {
        return !(availableModules.isEmpty() && substitutionRules.isEmpty());
    }
}
