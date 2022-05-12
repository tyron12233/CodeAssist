package com.tyron.builder.internal.classpath;

import com.google.common.hash.Hasher;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.internal.Pair;

import org.objectweb.asm.ClassVisitor;

import java.io.IOException;

public class CompositeTransformer implements CachedClasspathTransformer.Transform {
    private final CachedClasspathTransformer.Transform first;
    private final CachedClasspathTransformer.Transform second;

    public CompositeTransformer(CachedClasspathTransformer.Transform first, CachedClasspathTransformer.Transform second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public void applyConfigurationTo(Hasher hasher) {
        first.applyConfigurationTo(hasher);
        second.applyConfigurationTo(hasher);
    }

    @Override
    public Pair<RelativePath, ClassVisitor> apply(ClasspathEntryVisitor.Entry entry, ClassVisitor visitor) throws IOException {
        return first.apply(entry, second.apply(entry, visitor).right);
    }
}
