package com.tyron.builder.api.internal.tasks.compile.incremental.recomp;

import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.deps.DependentsSet;

import java.util.Set;

class SourceFileChangeProcessor {
    private final PreviousCompilation previousCompilation;

    public SourceFileChangeProcessor(PreviousCompilation previousCompilation) {
        this.previousCompilation = previousCompilation;
    }

    public void processChange(Set<String> classNames, RecompilationSpec spec) {
        spec.addClassesToCompile(classNames);
        DependentsSet actualDependents = previousCompilation.findDependentsOfSourceChanges(classNames);
        if (actualDependents.isDependencyToAll()) {
            spec.setFullRebuildCause(actualDependents.getDescription());
            return;
        }
        spec.addClassesToCompile(actualDependents.getAllDependentClasses());
        spec.addResourcesToGenerate(actualDependents.getDependentResources());
    }
}

