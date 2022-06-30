package com.tyron.builder.api.internal.tasks.compile.processing;


import com.tyron.builder.api.internal.tasks.compile.incremental.processing.AnnotationProcessorResult;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/**
 * An isolating processor must provide exactly one originating element
 * for each file it generates.
 */
public class IsolatingProcessor extends DelegatingProcessor {

    private final IsolatingProcessingStrategy strategy;

    public IsolatingProcessor(Processor delegate, AnnotationProcessorResult result) {
        super(delegate);
        this.strategy = new IsolatingProcessingStrategy(result);
    }

    @Override
    public final void init(ProcessingEnvironment processingEnv) {
        IncrementalFiler incrementalFiler = new IncrementalFiler(processingEnv.getFiler(), strategy);
        IncrementalProcessingEnvironment incrementalProcessingEnvironment = new IncrementalProcessingEnvironment(processingEnv, incrementalFiler);
        super.init(incrementalProcessingEnvironment);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        strategy.recordProcessingInputs(getSupportedAnnotationTypes(), annotations, roundEnv);
        return super.process(annotations, roundEnv);
    }
}
