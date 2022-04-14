package com.tyron.builder.api.internal.tasks.compile.incremental.recomp;

import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.deps.DependentsSet;
import com.tyron.builder.api.internal.tasks.compile.incremental.deps.ClassSetAnalysis;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PreviousCompilation {
    private final PreviousCompilationData data;
    private final ClassSetAnalysis classAnalysis;

    public PreviousCompilation(PreviousCompilationData data) {
        this.data = data;
        this.classAnalysis = new ClassSetAnalysis(data.getOutputSnapshot(), data.getAnnotationProcessingData(), data.getCompilerApiData());
    }

    @Nullable
    public ClassSetAnalysis getClasspath() {
        return new ClassSetAnalysis(data.getClasspathSnapshot());
    }

    public DependentsSet findDependentsOfClasspathChanges(ClassSetAnalysis.ClassSetDiff diff) {
        if (diff.getDependents().isDependencyToAll()) {
            return diff.getDependents();
        }
        return classAnalysis.findTransitiveDependents(diff.getDependents().getAllDependentClasses(), diff.getConstants());
    }

    public DependentsSet findDependentsOfSourceChanges(Set<String> classNames) {
        return classAnalysis.findTransitiveDependents(classNames, classNames.stream().collect(Collectors.toMap(Function.identity(), classAnalysis::getConstants)));
    }

    public Set<String> getTypesToReprocess(Set<String> compiledClasses) {
        return classAnalysis.getTypesToReprocess(compiledClasses);
    }

    public SourceFileClassNameConverter getSourceToClassConverter() {
        return new DefaultSourceFileClassNameConverter(data.getCompilerApiData().getSourceToClassMapping());
    }
}

