package com.tyron.builder.api.internal.tasks.compile.processing;

import com.tyron.builder.api.internal.tasks.compile.incremental.processing.AnnotationProcessorResult;
import com.tyron.builder.api.internal.tasks.compile.incremental.processing.IncrementalAnnotationProcessorType;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileManager;
import java.util.Set;

/**
 * The strategy used for dynamic processors.
 *
 * @see DynamicProcessor
 */
public class DynamicProcessingStrategy extends IncrementalProcessingStrategy {

    private IncrementalProcessingStrategy delegate;

    DynamicProcessingStrategy(String processorName, AnnotationProcessorResult result) {
        super(result);
        this.delegate = new NonIncrementalProcessingStrategy(processorName, result);
    }

    public void updateFromOptions(Set<String> supportedOptions) {
        if (supportedOptions.contains(IncrementalAnnotationProcessorType.ISOLATING.getProcessorOption())) {
            delegate = new IsolatingProcessingStrategy(result);
        } else if (supportedOptions.contains(IncrementalAnnotationProcessorType.AGGREGATING.getProcessorOption())) {
            delegate = new AggregatingProcessingStrategy(result);
        }
    }

    @Override
    public void recordProcessingInputs(Set<String> supportedAnnotationTypes, Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        delegate.recordProcessingInputs(supportedAnnotationTypes, annotations, roundEnv);
    }

    @Override
    public void recordGeneratedType(CharSequence name, Element[] originatingElements) {
        delegate.recordGeneratedType(name, originatingElements);
    }

    @Override
    public void recordGeneratedResource(JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName, Element[] originatingElements) {
        delegate.recordGeneratedResource(location, pkg, relativeName, originatingElements);
    }
}
