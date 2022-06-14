package com.tyron.builder.api.internal.tasks.compile.incremental.analyzer;

import com.google.common.hash.HashCode;
import com.google.common.io.ByteStreams;
import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.internal.tasks.compile.incremental.asm.ClassDependenciesVisitor;
import com.tyron.builder.api.internal.tasks.compile.incremental.deps.ClassAnalysis;
import com.tyron.builder.api.internal.cache.StringInterner;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;

public class DefaultClassDependenciesAnalyzer implements ClassDependenciesAnalyzer {

    private final StringInterner interner;

    public DefaultClassDependenciesAnalyzer(StringInterner interner) {
        this.interner = interner;
    }

    public ClassAnalysis getClassAnalysis(InputStream input) throws IOException {
        ClassReader reader = new ClassReader(ByteStreams.toByteArray(input));
        String className = reader.getClassName().replace("/", ".");
        return ClassDependenciesVisitor.analyze(className, reader, interner);
    }

    @Override
    public ClassAnalysis getClassAnalysis(HashCode classFileHash, FileTreeElement classFile) {
        try (InputStream input = classFile.open()) {
            return getClassAnalysis(input);
        } catch (IOException e) {
            throw new RuntimeException("Problems loading class analysis for " + classFile.toString());
        }
    }
}
