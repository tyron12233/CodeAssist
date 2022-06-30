package com.tyron.builder.api.internal.tasks.compile.incremental.analyzer;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.internal.tasks.compile.incremental.deps.ClassAnalysis;

public interface ClassDependenciesAnalyzer {
    ClassAnalysis getClassAnalysis(HashCode classFileHash, FileTreeElement classFile);
}

