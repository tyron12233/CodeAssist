package com.tyron.builder.api.internal.tasks.compile.incremental;


import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.tasks.compile.CleaningJavaCompiler;
import com.tyron.builder.api.internal.tasks.compile.JavaCompileSpec;
import com.tyron.builder.api.internal.tasks.compile.incremental.classpath.ClassSetAnalyzer;
import com.tyron.builder.api.internal.tasks.compile.incremental.recomp.CurrentCompilationAccess;
import com.tyron.builder.api.internal.tasks.compile.incremental.recomp.PreviousCompilationAccess;
import com.tyron.builder.api.internal.tasks.compile.incremental.recomp.RecompilationSpecProvider;
import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.language.base.internal.compile.Compiler;

public class IncrementalCompilerFactory {
    private final BuildOperationExecutor buildOperationExecutor;
    private final StringInterner interner;
    private final ClassSetAnalyzer classSetAnalyzer;

    public IncrementalCompilerFactory(BuildOperationExecutor buildOperationExecutor, StringInterner interner, ClassSetAnalyzer classSetAnalyzer) {
        this.buildOperationExecutor = buildOperationExecutor;
        this.interner = interner;
        this.classSetAnalyzer = classSetAnalyzer;
    }

    public <T extends JavaCompileSpec> Compiler<T> makeIncremental(CleaningJavaCompiler<T> cleaningJavaCompiler, FileTree sources, RecompilationSpecProvider recompilationSpecProvider) {
        Compiler<T> rebuildAllCompiler = createRebuildAllCompiler(cleaningJavaCompiler, sources);
        CurrentCompilationAccess currentCompilationAccess = new CurrentCompilationAccess(classSetAnalyzer, buildOperationExecutor);
        PreviousCompilationAccess previousCompilationAccess = new PreviousCompilationAccess(interner);
        Compiler<T> compiler = new SelectiveCompiler<>(cleaningJavaCompiler, rebuildAllCompiler, recompilationSpecProvider, currentCompilationAccess, previousCompilationAccess);
        return new IncrementalResultStoringCompiler<>(compiler, currentCompilationAccess, previousCompilationAccess);
    }

    private <T extends JavaCompileSpec> Compiler<T> createRebuildAllCompiler(CleaningJavaCompiler<T> cleaningJavaCompiler, FileTree sourceFiles) {
        return spec -> {
            spec.setSourceFiles(sourceFiles);
            return cleaningJavaCompiler.execute(spec);
        };
    }
}