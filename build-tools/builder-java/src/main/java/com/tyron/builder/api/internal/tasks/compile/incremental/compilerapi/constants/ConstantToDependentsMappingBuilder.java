package com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.constants;


import com.google.common.collect.Iterables;
import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.deps.DependentsSet;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A builder helper class to construct the ConstantToDependentsMapping
 */
public class ConstantToDependentsMappingBuilder implements Serializable {

    private final Map<String, Set<String>> privateDependents = new HashMap<>();
    private final Map<String, Set<String>> accessibleDependents = new HashMap<>();

    public ConstantToDependentsMappingBuilder addAccessibleDependents(String constantOrigin, Collection<String> dependents) {
        dependents.forEach(dependent -> addAccessibleDependent(constantOrigin, dependent));
        return this;
    }

    public ConstantToDependentsMappingBuilder addPrivateDependents(String constantOrigin, Collection<String> dependents) {
        dependents.forEach(dependent -> addPrivateDependent(constantOrigin, dependent));
        return this;
    }

    public ConstantToDependentsMappingBuilder addPrivateDependent(String constantOrigin, String dependent) {
        Set<String> accessibleDependents = this.accessibleDependents.computeIfAbsent(constantOrigin, k -> new HashSet<>());
        Set<String> privateDependents = this.privateDependents.computeIfAbsent(constantOrigin, k -> new HashSet<>());
        if (!accessibleDependents.contains(dependent)) {
            privateDependents.add(dependent);
        }
        return this;
    }

    public ConstantToDependentsMappingBuilder addAccessibleDependent(String constantOrigin, String dependent) {
        Set<String> accessibleDependents = this.accessibleDependents.computeIfAbsent(constantOrigin, k -> new HashSet<>());
        Set<String> privateDependents = this.privateDependents.computeIfAbsent(constantOrigin, k -> new HashSet<>());
        accessibleDependents.add(dependent);
        privateDependents.remove(dependent);
        return this;
    }

    public ConstantToDependentsMapping build() {
        Map<String, DependentsSet> constantDependents = new HashMap<>();
        for (String constantOrigin : Iterables
                .concat(privateDependents.keySet(), accessibleDependents.keySet())) {
            Set<String> privateDependents = this.privateDependents.getOrDefault(constantOrigin, Collections.emptySet());
            Set<String> accessibleDependents = this.accessibleDependents.getOrDefault(constantOrigin, Collections.emptySet());
            constantDependents.put(constantOrigin, DependentsSet.dependentClasses(privateDependents, accessibleDependents));
        }
        return new ConstantToDependentsMapping(constantDependents);
    }

}

