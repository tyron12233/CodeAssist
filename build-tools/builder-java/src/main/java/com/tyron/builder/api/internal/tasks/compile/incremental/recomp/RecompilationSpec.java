package com.tyron.builder.api.internal.tasks.compile.incremental.recomp;

import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.deps.GeneratedResource;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class RecompilationSpec {
    private final Set<String> classesToCompile = new LinkedHashSet<>();
    private final Collection<String> classesToProcess = new LinkedHashSet<>();
    private final Collection<GeneratedResource> resourcesToGenerate = new LinkedHashSet<>();
    private final PreviousCompilation previousCompilation;
    private String fullRebuildCause;

    public RecompilationSpec(PreviousCompilation previousCompilation) {
        this.previousCompilation = previousCompilation;
    }

    @Override
    public String toString() {
        return "RecompilationSpec{" +
               "classesToCompile=" + classesToCompile +
               ", classesToProcess=" + classesToProcess +
               ", resourcesToGenerate=" + resourcesToGenerate +
               ", fullRebuildCause='" + fullRebuildCause + '\'' +
               ", buildNeeded=" + isBuildNeeded() +
               ", fullRebuildNeeded=" + isFullRebuildNeeded() +
               '}';
    }

    public void addClassesToCompile(Collection<String> classes) {
        classesToCompile.addAll(classes);
    }

    public Set<String> getClassesToCompile() {
        return Collections.unmodifiableSet(classesToCompile);
    }

    public PreviousCompilation getPreviousCompilation() {
        return previousCompilation;
    }

    public void addClassesToProcess(Collection<String> classes) {
        classes.forEach(classToReprocess -> {
            if (classToReprocess.endsWith("package-info") || classToReprocess.equals("module-info")) {
                classesToCompile.add(classToReprocess);
            } else {
                classesToProcess.add(classToReprocess);
            }
        });
    }

    public Collection<String> getClassesToProcess() {
        return Collections.unmodifiableCollection(classesToProcess);
    }

    public void addResourcesToGenerate(Collection<GeneratedResource> resources) {
        resourcesToGenerate.addAll(resources);
    }

    public Collection<GeneratedResource> getResourcesToGenerate() {
        return Collections.unmodifiableCollection(resourcesToGenerate);
    }

    public boolean isBuildNeeded() {
        return isFullRebuildNeeded() || !classesToCompile.isEmpty() || !classesToProcess.isEmpty();
    }

    public boolean isFullRebuildNeeded() {
        return fullRebuildCause != null;
    }

    public String getFullRebuildCause() {
        return fullRebuildCause;
    }

    public void setFullRebuildCause(String description) {
        fullRebuildCause = description;
    }

}

