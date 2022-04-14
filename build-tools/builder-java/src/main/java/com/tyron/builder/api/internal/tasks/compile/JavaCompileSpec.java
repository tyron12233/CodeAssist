package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.api.internal.tasks.compile.processing.AnnotationProcessorDeclaration;

import java.io.File;
import java.util.List;
import java.util.Set;

public interface JavaCompileSpec extends JvmLanguageCompileSpec {
    MinimalJavaCompileOptions getCompileOptions();

    @Override
    File getDestinationDir();

    /**
     * The annotation processor path to use. When empty, no processing should be done. When not empty, processing should be done.
     */
    List<File> getAnnotationProcessorPath();

    void setAnnotationProcessorPath(List<File> path);

    void setEffectiveAnnotationProcessors(Set<AnnotationProcessorDeclaration> annotationProcessors);

    Set<AnnotationProcessorDeclaration> getEffectiveAnnotationProcessors();

    void setClasses(Set<String> classes);

    Set<String> getClasses();

    List<File> getModulePath();

    void setModulePath(List<File> modulePath);

    default boolean annotationProcessingConfigured() {
        return !getAnnotationProcessorPath().isEmpty() && !getCompileOptions().getCompilerArgs().contains("-proc:none");
    }
}
