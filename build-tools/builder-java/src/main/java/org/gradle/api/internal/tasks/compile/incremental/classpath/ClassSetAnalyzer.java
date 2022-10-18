package org.gradle.api.internal.tasks.compile.incremental.classpath;

import org.gradle.api.internal.tasks.compile.incremental.deps.ClassSetAnalysisData;

import java.io.File;

public interface ClassSetAnalyzer {
    ClassSetAnalysisData analyzeClasspathEntry(File classpathEntry);
    ClassSetAnalysisData analyzeOutputFolder(File outputFolder);
}

