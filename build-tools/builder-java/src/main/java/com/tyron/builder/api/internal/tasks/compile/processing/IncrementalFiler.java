package com.tyron.builder.api.internal.tasks.compile.processing;


import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import java.io.IOException;

/**
 * A decorator for the {@link Filer} which ensures that incremental
 * annotation processors don't break the incremental processing contract.
 */
public class IncrementalFiler implements Filer {
    private final Filer delegate;
    private final IncrementalProcessingStrategy strategy;

    IncrementalFiler(Filer delegate, IncrementalProcessingStrategy strategy) {
        this.delegate = delegate;
        if (strategy == null) {
            throw new NullPointerException("strategy");
        }
        this.strategy = strategy;
    }

    @Override
    public final JavaFileObject createSourceFile(CharSequence name, Element... originatingElements) throws IOException {
        strategy.recordGeneratedType(name, originatingElements);
        return delegate.createSourceFile(name, originatingElements);
    }

    @Override
    public final JavaFileObject createClassFile(CharSequence name, Element... originatingElements) throws IOException {
        strategy.recordGeneratedType(name, originatingElements);
        return delegate.createClassFile(name, originatingElements);
    }

    @Override
    public final FileObject createResource(JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName, Element... originatingElements) throws IOException {
        // Prefer having javac validate the location over us, by calling it first.
        FileObject resource = delegate.createResource(location, pkg, relativeName, originatingElements);
        strategy.recordGeneratedResource(location, pkg, relativeName, originatingElements);
        return resource;
    }

    @Override
    public final FileObject getResource(JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName) throws IOException {
        strategy.recordAccessedResource(location, pkg, relativeName);
        return delegate.getResource(location, pkg, relativeName);
    }
}
