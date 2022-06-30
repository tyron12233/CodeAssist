package com.tyron.builder.api.internal.tasks.compile.processing;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Locale;
import java.util.Map;

/**
 * A decorator for the {@link ProcessingEnvironment} provided by the Java compiler,
 * which allows us to intercept calls from annotation processors in order to validate
 * their behavior.
 */
class IncrementalProcessingEnvironment implements ProcessingEnvironment {
    private final ProcessingEnvironment delegate;
    private final IncrementalFiler filer;

    IncrementalProcessingEnvironment(ProcessingEnvironment delegate, IncrementalFiler filer) {
        this.delegate = delegate;
        this.filer = filer;
    }

    @Override
    public Map<String, String> getOptions() {
        return delegate.getOptions();
    }

    @Override
    public Messager getMessager() {
        return delegate.getMessager();
    }

    @Override
    public Filer getFiler() {
        return filer;
    }

    @Override
    public Elements getElementUtils() {
        return delegate.getElementUtils();
    }

    @Override
    public Types getTypeUtils() {
        return delegate.getTypeUtils();
    }

    @Override
    public SourceVersion getSourceVersion() {
        return delegate.getSourceVersion();
    }

    @Override
    public Locale getLocale() {
        return delegate.getLocale();
    }
}
