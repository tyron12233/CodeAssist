package com.tyron.builder.internal.classpath;

import com.google.common.hash.Hasher;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.internal.Pair;

import org.objectweb.asm.ClassVisitor;

import java.io.IOException;

public class InstrumentingTransformer implements CachedClasspathTransformer.Transform {
    @Override
    public void applyConfigurationTo(Hasher hasher) {

    }

    @Override
    public Pair<RelativePath, ClassVisitor> apply(ClasspathEntryVisitor.Entry entry,
                                                  ClassVisitor visitor) throws IOException {
        throw new UnsupportedOperationException();
    }
}
