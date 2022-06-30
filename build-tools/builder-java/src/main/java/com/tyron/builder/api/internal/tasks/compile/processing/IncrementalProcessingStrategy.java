package com.tyron.builder.api.internal.tasks.compile.processing;

import com.tyron.builder.api.internal.tasks.compile.incremental.processing.AnnotationProcessorResult;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileManager;
import java.util.Set;

/**
 * The strategy that updates the processing result according to the type and runtime behavior of a processor.
 */
abstract class IncrementalProcessingStrategy {
    protected final AnnotationProcessorResult result;

    IncrementalProcessingStrategy(AnnotationProcessorResult result) {
        this.result = result;
    }

    public abstract void recordProcessingInputs(Set<String> supportedAnnotationTypes, Set<? extends TypeElement> annotations, RoundEnvironment roundEnv);

    public abstract void recordGeneratedType(CharSequence name, Element[] originatingElements);

    public abstract void recordGeneratedResource(JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName, Element[] originatingElements);

    /**
     * We don't trigger a full recompile on resource reads, because we already trigger a full recompile when any
     * resource changes.
     */
    public final void recordAccessedResource(JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName) {
    }
}

