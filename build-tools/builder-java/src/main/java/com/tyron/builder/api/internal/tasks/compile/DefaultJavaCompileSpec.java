package com.tyron.builder.api.internal.tasks.compile;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.tyron.builder.api.internal.tasks.compile.processing.AnnotationProcessorDeclaration;
import com.tyron.builder.api.tasks.compile.CompileOptions;

import java.io.File;
import java.util.List;
import java.util.Set;

public class DefaultJavaCompileSpec extends DefaultJvmLanguageCompileSpec implements JavaCompileSpec {
    private MinimalJavaCompileOptions compileOptions;
    private List<File> annotationProcessorPath;
    private Set<AnnotationProcessorDeclaration> effectiveAnnotationProcessors;
    private Set<String> classes;
    private List<File> modulePath;
    private List<File> sourceRoots;

    @Override
    public MinimalJavaCompileOptions getCompileOptions() {
        return compileOptions;
    }

    public void setCompileOptions(CompileOptions compileOptions) {
        this.compileOptions = new MinimalJavaCompileOptions(compileOptions);
    }

    @Override
    public List<File> getAnnotationProcessorPath() {
        return annotationProcessorPath;
    }

    @Override
    public void setAnnotationProcessorPath(List<File> annotationProcessorPath) {
        this.annotationProcessorPath = annotationProcessorPath;
    }

    @Override
    public Set<AnnotationProcessorDeclaration> getEffectiveAnnotationProcessors() {
        return effectiveAnnotationProcessors;
    }

    @Override
    public void setEffectiveAnnotationProcessors(Set<AnnotationProcessorDeclaration> annotationProcessors) {
        this.effectiveAnnotationProcessors = annotationProcessors;
    }

    @Override
    public Set<String> getClasses() {
        return classes;
    }

    @Override
    public void setClasses(Set<String> classes) {
        this.classes = classes;
    }

    @Override
    public List<File> getModulePath() {
        if (modulePath == null || modulePath.isEmpty()) {
            int i = compileOptions.getCompilerArgs().indexOf("--module-path");
            if (i >= 0) {
                // This is kept for backward compatibility - may be removed in the future
                String[] modules = compileOptions.getCompilerArgs().get(i + 1).split(File.pathSeparator);
                modulePath = Lists.newArrayListWithCapacity(modules.length);
                for (String module : modules) {
                    modulePath.add(new File(module));
                }
            } else if (modulePath == null) {
                modulePath = ImmutableList.of();
            }
        }
        return modulePath;
    }

    @Override
    public void setModulePath(List<File> modulePath) {
        this.modulePath = modulePath;
    }

    @Override
    public List<File> getSourceRoots() {
        return sourceRoots;
    }

    @Override
    public void setSourcesRoots(List<File> sourcesRoots) {
        this.sourceRoots = sourcesRoots;
    }
}
