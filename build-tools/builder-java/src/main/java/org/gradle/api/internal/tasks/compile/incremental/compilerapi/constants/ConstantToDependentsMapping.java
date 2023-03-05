package org.gradle.api.internal.tasks.compile.incremental.compilerapi.constants;

import org.gradle.api.internal.tasks.compile.incremental.compilerapi.deps.DependentsSet;

import java.util.Map;

import java.util.Collections;
import java.util.Map;

public class ConstantToDependentsMapping {

    private final Map<String, DependentsSet> constantDependents;

    public ConstantToDependentsMapping(Map<String, DependentsSet> constantDependents) {
        this.constantDependents = constantDependents;
    }

    public Map<String, DependentsSet> getConstantDependents() {
        return constantDependents;
    }

    public DependentsSet getConstantDependentsForClass(String constantOrigin) {
        return constantDependents.getOrDefault(constantOrigin, DependentsSet.empty());
    }

    public static ConstantToDependentsMapping empty() {
        return new ConstantToDependentsMapping(Collections.emptyMap());
    }

    public static ConstantToDependentsMappingBuilder builder() {
        return new ConstantToDependentsMappingBuilder();
    }

}

