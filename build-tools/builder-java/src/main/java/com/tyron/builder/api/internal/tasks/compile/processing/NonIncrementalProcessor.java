package com.tyron.builder.api.internal.tasks.compile.processing;

import com.tyron.builder.api.internal.tasks.compile.incremental.processing.AnnotationProcessorResult;

import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/**
 * An annotation processor that did not opt into incremental processing.
 * Any use of such a processor will result in full recompilation.
 * As opposed to the other processor implementations, this one will not
 * decorate the processing environment, because there are some processors
 * that cast it to its implementation type, e.g. JavacProcessingEnvironment.
 */
public class NonIncrementalProcessor extends DelegatingProcessor {

    private final NonIncrementalProcessingStrategy strategy;

    public NonIncrementalProcessor(Processor delegate, AnnotationProcessorResult result) {
        super(delegate);
        this.strategy = new NonIncrementalProcessingStrategy(delegate.getClass().getName(), result);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        strategy.recordProcessingInputs(getSupportedAnnotationTypes(), annotations, roundEnv);
        return super.process(annotations, roundEnv);
    }
}

