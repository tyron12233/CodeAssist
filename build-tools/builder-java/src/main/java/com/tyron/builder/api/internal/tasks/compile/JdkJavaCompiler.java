package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.api.JavaVersion;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.classpath.DefaultClassPath;
import com.tyron.builder.api.internal.tasks.compile.processing.AnnotationProcessorDeclaration;
import com.tyron.builder.api.internal.tasks.compile.reflect.GradleStandardJavaFileManager;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.language.base.internal.compile.Compiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

public class JdkJavaCompiler implements Compiler<JavaCompileSpec>, Serializable {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdkJavaCompiler.class);
    private final Factory<JavaCompiler> javaHomeBasedJavaCompilerFactory;

    @Inject
    public JdkJavaCompiler(Factory<JavaCompiler> javaHomeBasedJavaCompilerFactory) {
        this.javaHomeBasedJavaCompilerFactory = javaHomeBasedJavaCompilerFactory;
    }

    @Override
    public WorkResult execute(JavaCompileSpec spec) {
        LOGGER.info("Compiling with JDK Java compiler API.");

        ApiCompilerResult result = new ApiCompilerResult();
        JavaCompiler.CompilationTask task = createCompileTask(spec, result);
        boolean success = task.call();
        if (!success) {
            throw new CompilationFailedException();
        }
        return result;
    }

    private JavaCompiler.CompilationTask createCompileTask(JavaCompileSpec spec, ApiCompilerResult result) {
        List<String> options = new JavaCompilerArgumentsBuilder(spec).build();
        JavaCompiler compiler = javaHomeBasedJavaCompilerFactory.create();
        MinimalJavaCompileOptions compileOptions = spec.getCompileOptions();
        Charset charset = compileOptions.getEncoding() != null ? Charset.forName(compileOptions.getEncoding()) : null;
        StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(null, null, charset);
        Iterable<? extends JavaFileObject> compilationUnits = standardFileManager.getJavaFileObjectsFromFiles(spec.getSourceFiles());
        boolean hasEmptySourcepaths = JavaVersion.current().isJava9Compatible() && emptySourcepathIn(options);
        JavaFileManager fileManager = GradleStandardJavaFileManager
                .wrap(standardFileManager, DefaultClassPath
                .of(spec.getAnnotationProcessorPath()), hasEmptySourcepaths);
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, null, options, spec.getClasses(), compilationUnits);
        if (compiler instanceof IncrementalCompilationAwareJavaCompiler) {
            task = ((IncrementalCompilationAwareJavaCompiler) compiler).makeIncremental(task, result.getSourceClassesMapping(), result.getConstantsAnalysisResult(), new CompilationSourceDirs(spec));
        }
        Set<AnnotationProcessorDeclaration> annotationProcessors = spec.getEffectiveAnnotationProcessors();
        task = new AnnotationProcessingCompileTask(task, annotationProcessors, spec.getAnnotationProcessorPath(), result.getAnnotationProcessingResult());
        task = new ResourceCleaningCompilationTask(task, fileManager);
        return task;
    }

    private static boolean emptySourcepathIn(List<String> options) {
        Iterator<String> optionsIter = options.iterator();
        while (optionsIter.hasNext()) {
            String current = optionsIter.next();
            if (current.equals("-sourcepath") || current.equals("--source-path")) {
                return optionsIter.next().isEmpty();
            }
        }
        return false;
    }
}
