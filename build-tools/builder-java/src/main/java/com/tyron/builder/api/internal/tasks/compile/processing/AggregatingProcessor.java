package com.tyron.builder.api.internal.tasks.compile.processing;

import com.tyron.builder.api.internal.tasks.compile.incremental.processing.AnnotationProcessorResult;

import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;


/**
 * An aggregating processor can have zero to many originating elements for each generated file.
 */
public final class AggregatingProcessor extends DelegatingProcessor {

    private final IncrementalProcessingStrategy strategy;

    public AggregatingProcessor(Processor delegate, AnnotationProcessorResult result) {
        super(delegate);
        this.strategy = new AggregatingProcessingStrategy(result);
    }

    @Override
    public final void init(ProcessingEnvironment processingEnv) {
        IncrementalFiler incrementalFiler = new IncrementalFiler(processingEnv.getFiler(), strategy);
        IncrementalProcessingEnvironment incrementalProcessingEnvironment = new IncrementalProcessingEnvironment(processingEnv, incrementalFiler);
        super.init(incrementalProcessingEnvironment);
    }

    @Override
    public final boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        strategy.recordProcessingInputs(getSupportedAnnotationTypes(), annotations, roundEnv);
        return super.process(annotations, roundEnv);
    }


}
