package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.constants.ConstantsAnalysisResult;

import javax.tools.JavaCompiler;
import java.util.Map;
import java.util.Set;

public interface IncrementalCompilationAwareJavaCompiler extends JavaCompiler {
    JavaCompiler.CompilationTask makeIncremental(JavaCompiler.CompilationTask task, Map<String, Set<String>> sourceToClassMapping, ConstantsAnalysisResult constantsAnalysisResult, CompilationSourceDirs compilationSourceDirs);
}
