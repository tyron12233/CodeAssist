package org.gradle.api.internal.tasks.compile.incremental;


import org.gradle.api.file.FileTree;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.api.internal.tasks.compile.CleaningJavaCompiler;
import org.gradle.api.internal.tasks.compile.JavaCompileSpec;
import org.gradle.api.internal.tasks.compile.incremental.classpath.ClassSetAnalyzer;
import org.gradle.api.internal.tasks.compile.incremental.recomp.CurrentCompilationAccess;
import org.gradle.api.internal.tasks.compile.incremental.recomp.PreviousCompilationAccess;
import org.gradle.api.internal.tasks.compile.incremental.recomp.RecompilationSpecProvider;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.language.base.internal.compile.Compiler;

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