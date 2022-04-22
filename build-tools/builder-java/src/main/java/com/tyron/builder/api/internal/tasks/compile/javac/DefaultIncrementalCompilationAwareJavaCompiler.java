package com.tyron.builder.api.internal.tasks.compile.javac;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.reflect.DirectInstantiator;
import com.tyron.builder.api.internal.tasks.compile.CompilationSourceDirs;
import com.tyron.builder.api.internal.tasks.compile.IncrementalCompilationAwareJavaCompiler;
import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.constants.ConstantsAnalysisResult;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.lang.model.SourceVersion;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

public class DefaultIncrementalCompilationAwareJavaCompiler implements IncrementalCompilationAwareJavaCompiler {

    // Copied from ToolProvider.defaultJavaCompilerName
    private static final String DEFAULT_COMPILER_IMPL_NAME = "com.sun.tools.javac.api.JavacTool";

//    private final ClassLoader isolatedToolsLoader;
//    private final boolean isJava9Compatible;

    private Class<JavaCompiler.CompilationTask> incrementalCompileTaskClass;

    private final JavaCompiler delegate;

    public DefaultIncrementalCompilationAwareJavaCompiler(JavaCompiler delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompilationTask getTask(Writer out, JavaFileManager fileManager, DiagnosticListener<? super JavaFileObject> diagnosticListener, Iterable<String> options, Iterable<String> classes, Iterable<? extends JavaFileObject> compilationUnits) {
        return delegate.getTask(out, fileManager, diagnosticListener, options, classes, compilationUnits);
    }

    @Override
    public StandardJavaFileManager getStandardFileManager(DiagnosticListener<? super JavaFileObject> diagnosticListener, Locale locale, Charset charset) {
        return delegate.getStandardFileManager(diagnosticListener, locale, charset);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public int run(InputStream in, OutputStream out, OutputStream err, String... arguments) {
        return delegate.run(in, out, err, arguments);
    }

    @Override
    public Set<SourceVersion> getSourceVersions() {
        return delegate.getSourceVersions();
    }

    @Override
    public int isSupportedOption(String option) {
        return delegate.isSupportedOption(option);
    }

    @Override
    public JavaCompiler.CompilationTask makeIncremental(JavaCompiler.CompilationTask task, Map<String, Set<String>> sourceToClassMapping, ConstantsAnalysisResult constantsAnalysisResult, CompilationSourceDirs compilationSourceDirs) {
        ensureCompilerTask();
        return DirectInstantiator.instantiate(incrementalCompileTaskClass, task,
                (Function<File, Optional<String>>) compilationSourceDirs::relativize,
                (Consumer<Map<String, Set<String>>>) sourceToClassMapping::putAll,
                (BiConsumer<String, String>) constantsAnalysisResult::addPublicDependent,
                (BiConsumer<String, String>) constantsAnalysisResult::addPrivateDependent
        );
    }

    private void ensureCompilerTask() {
        if (incrementalCompileTaskClass == null) {
            synchronized (this) {
                try {
                    incrementalCompileTaskClass = Cast.uncheckedCast(Class.forName("com.tyron.builder.internal.compiler.java.IncrementalCompileTask"));
                } catch (ClassNotFoundException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        }
    }
}
