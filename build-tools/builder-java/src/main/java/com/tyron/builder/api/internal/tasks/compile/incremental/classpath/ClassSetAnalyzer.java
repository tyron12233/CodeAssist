package com.tyron.builder.api.internal.tasks.compile.incremental.classpath;

import com.tyron.builder.api.internal.tasks.compile.incremental.deps.ClassSetAnalysisData;

import java.io.File;

public interface ClassSetAnalyzer {
    ClassSetAnalysisData analyzeClasspathEntry(File classpathEntry);
    ClassSetAnalysisData analyzeOutputFolder(File outputFolder);
}

