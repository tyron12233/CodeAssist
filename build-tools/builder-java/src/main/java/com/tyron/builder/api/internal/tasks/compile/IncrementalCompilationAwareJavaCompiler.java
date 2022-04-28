package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.constants.ConstantsAnalysisResult;

import java.util.Map;
import java.util.Set;

import javax.tools.JavaCompiler;

public interface IncrementalCompilationAwareJavaCompiler extends JavaCompiler {
    JavaCompiler.CompilationTask makeIncremental(JavaCompiler.CompilationTask task, Map<String, Set<String>> sourceToClassMapping, ConstantsAnalysisResult constantsAnalysisResult, CompilationSourceDirs compilationSourceDirs);
}

