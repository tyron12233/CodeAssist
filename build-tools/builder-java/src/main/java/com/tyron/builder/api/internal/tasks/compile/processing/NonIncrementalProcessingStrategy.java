package com.tyron.builder.api.internal.tasks.compile.processing;

import static com.tyron.builder.api.internal.tasks.compile.incremental.processing.IncrementalAnnotationProcessorType.UNKNOWN;

import com.tyron.builder.api.internal.tasks.compile.incremental.processing.AnnotationProcessorResult;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileManager;
import java.util.Set;

/**
 * The strategy used for non-incremental annotation processors.
 * @see NonIncrementalProcessor
 */
public class NonIncrementalProcessingStrategy extends IncrementalProcessingStrategy {
    private final String name;

    NonIncrementalProcessingStrategy(String name, AnnotationProcessorResult result) {
        super(result);
        this.name = name;
        result.setType(UNKNOWN);
    }

    @Override
    public void recordProcessingInputs(Set<String> supportedAnnotationTypes, Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        result.setFullRebuildCause(name + " is not incremental");
    }

    @Override
    public void recordGeneratedType(CharSequence name, Element[] originatingElements) {

    }

    @Override
    public void recordGeneratedResource(JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName, Element[] originatingElements) {

    }
}
