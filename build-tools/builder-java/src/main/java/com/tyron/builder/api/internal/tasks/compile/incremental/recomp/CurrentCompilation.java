package com.tyron.builder.api.internal.tasks.compile.incremental.recomp;

import com.google.common.collect.Iterables;
import com.tyron.builder.api.internal.tasks.compile.JavaCompileSpec;
import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.deps.DependentsSet;
import com.tyron.builder.api.internal.tasks.compile.incremental.deps.ClassSetAnalysis;

import java.io.File;
import java.util.Collection;

public class CurrentCompilation {
    private final JavaCompileSpec spec;
    private final CurrentCompilationAccess classpathSnapshotter;

    public CurrentCompilation(JavaCompileSpec spec, CurrentCompilationAccess classpathSnapshotter) {
        this.spec = spec;
        this.classpathSnapshotter = classpathSnapshotter;
    }

    public Collection<File> getAnnotationProcessorPath() {
        return spec.getAnnotationProcessorPath();
    }

    public DependentsSet findDependentsOfClasspathChanges(PreviousCompilation previous) {
        ClassSetAnalysis currentClasspath = getClasspath();
        ClassSetAnalysis previousClasspath = previous.getClasspath();
        if (previousClasspath == null) {
            return DependentsSet.dependencyToAll("classpath data of previous compilation is incomplete");
        }
        ClassSetAnalysis.ClassSetDiff classpathChanges = currentClasspath.findChangesSince(previousClasspath);
        return previous.findDependentsOfClasspathChanges(classpathChanges);
    }

    private ClassSetAnalysis getClasspath() {
        return new ClassSetAnalysis(classpathSnapshotter.getClasspathSnapshot(
                Iterables.concat(spec.getCompileClasspath(), spec.getModulePath())));
    }

}

