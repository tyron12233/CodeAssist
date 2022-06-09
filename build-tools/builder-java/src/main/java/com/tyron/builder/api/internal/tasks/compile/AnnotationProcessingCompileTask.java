package com.tyron.builder.api.internal.tasks.compile;

import static com.tyron.builder.api.internal.tasks.compile.filter.AnnotationProcessorFilter.getFilteredClassLoader;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.internal.classpath.DefaultClassPath;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.api.internal.tasks.compile.incremental.processing.AnnotationProcessingResult;
import com.tyron.builder.api.internal.tasks.compile.incremental.processing.AnnotationProcessorResult;
import com.tyron.builder.api.internal.tasks.compile.incremental.processing.IncrementalAnnotationProcessorType;
import com.tyron.builder.api.internal.tasks.compile.processing.AggregatingProcessor;
import com.tyron.builder.api.internal.tasks.compile.processing.AnnotationProcessorDeclaration;
import com.tyron.builder.api.internal.tasks.compile.processing.DynamicProcessor;
import com.tyron.builder.api.internal.tasks.compile.processing.IsolatingProcessor;
import com.tyron.builder.api.internal.tasks.compile.processing.NonIncrementalProcessor;
import com.tyron.builder.api.internal.tasks.compile.processing.SupportedOptionsCollectingProcessor;
import com.tyron.builder.api.internal.tasks.compile.processing.TimeTrackingProcessor;
import com.tyron.builder.util.internal.GUtil;
import com.tyron.common.TestUtil;

import org.codehaus.groovy.reflection.android.AndroidSupport;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Set;

import javax.tools.JavaCompiler;


import javax.annotation.processing.Processor;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Wraps another {@link JavaCompiler.CompilationTask} and sets up its annotation processors
 * according to the provided processor declarations and processor path. Incremental processors
 * are decorated in order to validate their behavior.
 *
 * This class also serves a purpose when incremental annotation processing is not active.
 * It replaces the normal processor discovery, which suffers from file descriptor leaks
 * on Java 8 and below. Our own discovery mechanism does not have that issue.
 *
 * This also prevents the Gradle API from leaking into the annotation processor classpath.
 */
class AnnotationProcessingCompileTask implements JavaCompiler.CompilationTask {

    private final JavaCompiler.CompilationTask delegate;
    private final Set<AnnotationProcessorDeclaration> processorDeclarations;
    private final List<File> annotationProcessorPath;
    private final AnnotationProcessingResult result;

    private ClassLoader processorClassloader;
    private boolean called;

    AnnotationProcessingCompileTask(JavaCompiler.CompilationTask delegate, Set<AnnotationProcessorDeclaration> processorDeclarations, List<File> annotationProcessorPath, AnnotationProcessingResult result) {
        this.delegate = delegate;
        this.processorDeclarations = processorDeclarations;
        this.annotationProcessorPath = annotationProcessorPath;
        this.result = result;
    }

    @Override
    public void addModules(Iterable<String> moduleNames) {
    }

    @Override
    public void setProcessors(Iterable<? extends Processor> processors) {
        throw new UnsupportedOperationException("This decorator already handles annotation processing");
    }

    @Override
    public void setLocale(Locale locale) {
        delegate.setLocale(locale);
    }

    @Override
    public Boolean call() {
        if (called) {
            throw new IllegalStateException("Cannot reuse a compilation task");
        }
        called = true;
        try {
            setupProcessors();
            return delegate.call();
        } finally {
            cleanupProcessors();
        }
    }

    private void setupProcessors() {
        processorClassloader = createProcessorClassLoader();
        List<Processor> processors = new ArrayList<Processor>(processorDeclarations.size());
        if (!processorDeclarations.isEmpty()) {
            SupportedOptionsCollectingProcessor supportedOptionsCollectingProcessor = new SupportedOptionsCollectingProcessor();
            for (AnnotationProcessorDeclaration declaredProcessor : processorDeclarations) {
                AnnotationProcessorResult processorResult = new AnnotationProcessorResult(result, declaredProcessor.getClassName());
                result.getAnnotationProcessorResults().add(processorResult);

                Class<?> processorClass = loadProcessor(declaredProcessor);
                Processor processor = instantiateProcessor(processorClass);
                supportedOptionsCollectingProcessor.addProcessor(processor);
                processor = decorateForIncrementalProcessing(processor, declaredProcessor.getType(), processorResult);
                processor = decorateForTimeTracking(processor, processorResult);
                processors.add(processor);
            }
            processors.add(supportedOptionsCollectingProcessor);
        }
        delegate.setProcessors(processors);
    }

    ClassLoader createProcessorClassLoader() {
        if (AndroidSupport.isRunningAndroid()) {
            return GUtil.uncheckedCall(() -> {
                Class<?> dexClassLoader = Class.forName("dalvik.system.DexClassLoader");
                Constructor<?> constructor = dexClassLoader.getConstructor(
                        String.class,
                        String.class,
                        String.class,
                        ClassLoader.class
                );

                String paths = annotationProcessorPath.stream().map(File::getAbsolutePath)
                        .collect(Collectors.joining(File.pathSeparator));
                return (ClassLoader) constructor.newInstance(paths, null, null, delegate.getClass().getClassLoader());
            });
        }
        return new URLClassLoader(
                DefaultClassPath.of(annotationProcessorPath).getAsURLArray(),
                getFilteredClassLoader(delegate.getClass().getClassLoader())
        );
    }

    private Class<?> loadProcessor(AnnotationProcessorDeclaration declaredProcessor) {
        try {
            return processorClassloader.loadClass(declaredProcessor.getClassName());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Annotation processor '" + declaredProcessor.getClassName() + "' not found", unwrapCause(e));
        }
    }

    private Processor instantiateProcessor(Class<?> processorClass) {
        try {
            return (Processor) processorClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not instantiate annotation processor '" + processorClass.getName() + "'", unwrapCause(e));
        }
    }

    private Throwable unwrapCause(Throwable throwable) {
        if (throwable instanceof InvocationTargetException) {
            return throwable.getCause();
        }
        return throwable;
    }

    private Processor decorateForIncrementalProcessing(Processor processor, IncrementalAnnotationProcessorType type, AnnotationProcessorResult processorResult) {
        switch (type) {
            case ISOLATING:
                return new IsolatingProcessor(processor, processorResult);
            case AGGREGATING:
                return new AggregatingProcessor(processor, processorResult);
            case DYNAMIC:
                return new DynamicProcessor(processor, processorResult);
            default:
                return new NonIncrementalProcessor(processor, processorResult);
        }
    }

    private Processor decorateForTimeTracking(Processor processor, AnnotationProcessorResult processorResult) {
        return new TimeTrackingProcessor(processor, processorResult);
    }

    private void cleanupProcessors() {
        CompositeStoppable.stoppable(processorClassloader).stop();
    }
}
