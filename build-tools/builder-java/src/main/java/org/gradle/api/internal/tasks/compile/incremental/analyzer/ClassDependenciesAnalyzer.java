package org.gradle.api.internal.tasks.compile.incremental.analyzer;

import com.google.common.hash.HashCode;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.internal.tasks.compile.incremental.deps.ClassAnalysis;

public interface ClassDependenciesAnalyzer {
    ClassAnalysis getClassAnalysis(HashCode classFileHash, FileTreeElement classFile);
}

