package com.tyron.builder.api.internal.tasks.compile.processing;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects all registered processors' supported options.
 *
 * <p>This is a workaround for https://bugs.openjdk.java.net/browse/JDK-8162455, which
 * will be triggered a lot more during incremental compilations, and can fail builds
 * when combined with {@code -Werror}.
 *
 * <p>This processor needs to be added last to make sure that all other processors
 * have been {@linkplain Processor#init(ProcessingEnvironment) initialized} when
 * {@link #getSupportedOptions()} is called.
 */
public class SupportedOptionsCollectingProcessor extends AbstractProcessor {
    private final List<Processor> processors = new ArrayList<Processor>();

    public void addProcessor(Processor processor) {
        processors.add(processor);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Collections.singleton("*");
    }

    @Override
    public Set<String> getSupportedOptions() {
        Set<String> supportedOptions = new HashSet<String>();
        for (Processor processor : processors) {
            supportedOptions.addAll(processor.getSupportedOptions());
        }
        return supportedOptions;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {
        return false;
    }
}

