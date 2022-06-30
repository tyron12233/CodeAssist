package com.tyron.builder.api.internal.tasks.compile.processing;

import com.tyron.builder.api.internal.tasks.compile.incremental.processing.AnnotationProcessorResult;
import com.tyron.builder.api.internal.tasks.compile.incremental.processing.IncrementalAnnotationProcessorType;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/**
 * An annotation processor which can decide whether it is isolating, aggregating or non-incremental at runtime.
 * It needs to return its type through the {@link #getSupportedOptions()} method in the format defined by
 * {@link IncrementalAnnotationProcessorType#getProcessorOption()}.
 */
public class DynamicProcessor extends DelegatingProcessor {
    private final DynamicProcessingStrategy strategy;

    public DynamicProcessor(Processor delegate, AnnotationProcessorResult result) {
        super(delegate);
        strategy = new DynamicProcessingStrategy(delegate.getClass().getName(), result);
    }

    @Override
    public void init(ProcessingEnvironment processingEnv) {
        IncrementalFiler incrementalFiler = new IncrementalFiler(processingEnv.getFiler(), strategy);
        IncrementalProcessingEnvironment incrementalEnvironment = new IncrementalProcessingEnvironment(processingEnv, incrementalFiler);
        super.init(incrementalEnvironment);
        strategy.updateFromOptions(getSupportedOptions());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        strategy.recordProcessingInputs(getSupportedAnnotationTypes(), annotations, roundEnv);
        return super.process(annotations, roundEnv);
    }
}
