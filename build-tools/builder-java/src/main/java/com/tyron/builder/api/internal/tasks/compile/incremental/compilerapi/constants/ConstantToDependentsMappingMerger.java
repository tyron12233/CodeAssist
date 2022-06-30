package com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.constants;

import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.deps.DependentsSet;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * Class used to merge new constants mapping from compiler api results with old results
 */
public class ConstantToDependentsMappingMerger {

    public ConstantToDependentsMapping merge(ConstantToDependentsMapping newMapping, @Nullable ConstantToDependentsMapping oldMapping, Set<String> changedClasses) {
        if (oldMapping == null) {
            oldMapping = ConstantToDependentsMapping.empty();
        }
        return updateClassToConstantsMapping(newMapping, oldMapping, changedClasses);
    }

    private ConstantToDependentsMapping updateClassToConstantsMapping(ConstantToDependentsMapping newMapping, ConstantToDependentsMapping oldMapping, Set<String> changedClasses) {
        ConstantToDependentsMappingBuilder builder = ConstantToDependentsMapping.builder();
        oldMapping.getConstantDependents().keySet().stream()
                .filter(constantOrigin -> !changedClasses.contains(constantOrigin))
                .forEach(constantOrigin -> {
                    DependentsSet dependents = oldMapping.getConstantDependentsForClass(constantOrigin);
                    Set<String> accessibleDependents = new HashSet<>(dependents.getAccessibleDependentClasses());
                    accessibleDependents.removeIf(changedClasses::contains);
                    builder.addAccessibleDependents(constantOrigin, accessibleDependents);
                    Set<String> privateDependents = new HashSet<>(dependents.getPrivateDependentClasses());
                    privateDependents.removeIf(changedClasses::contains);
                    builder.addPrivateDependents(constantOrigin, privateDependents);
                });
        newMapping.getConstantDependents().keySet()
                .forEach(constantOrigin -> {
                    DependentsSet dependents = newMapping.getConstantDependentsForClass(constantOrigin);
                    builder.addAccessibleDependents(constantOrigin, dependents.getAccessibleDependentClasses());
                    builder.addPrivateDependents(constantOrigin, dependents.getPrivateDependentClasses());
                });
        return builder.build();
    }

}

