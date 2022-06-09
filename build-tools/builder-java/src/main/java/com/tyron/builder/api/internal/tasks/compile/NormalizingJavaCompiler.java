package com.tyron.builder.api.internal.tasks.compile;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.WorkResults;
import com.tyron.builder.language.base.internal.compile.Compiler;
import com.tyron.builder.util.internal.CollectionUtils;
import com.tyron.builder.util.internal.GFileUtils;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;

/**
 * A Java {@link Compiler} which does some normalization of the compile configuration and behaviour before delegating to some other compiler.
 */
public class NormalizingJavaCompiler implements Compiler<JavaCompileSpec> {
    private static final Logger LOGGER = Logging.getLogger(NormalizingJavaCompiler.class);
    private final Compiler<JavaCompileSpec> delegate;

    public NormalizingJavaCompiler(Compiler<JavaCompileSpec> delegate) {
        this.delegate = delegate;
    }

    @Override
    public WorkResult execute(JavaCompileSpec spec) {
        resolveAndFilterSourceFiles(spec);
        resolveNonStringsInCompilerArgs(spec);
        logSourceFiles(spec);
        logCompilerArguments(spec);
        return delegateAndHandleErrors(spec);
    }

    private void resolveAndFilterSourceFiles(JavaCompileSpec spec) {
        // this mimics the behavior of the Ant javac task (and therefore AntJavaCompiler),
        // which silently excludes files not ending in .java
        Iterable<File> javaOnly = Iterables.filter(spec.getSourceFiles(), NormalizingJavaCompiler::hasJavaExtension);
        spec.setSourceFiles(ImmutableSet.copyOf(javaOnly));
    }

    private static boolean hasJavaExtension(@Nullable File input) {
        if (input == null) {
            return false;
        }
        return GFileUtils.hasExtensionIgnoresCase(input.getName(), ".java");
    }

    private void resolveNonStringsInCompilerArgs(JavaCompileSpec spec) {
        // in particular, this is about GStrings
        spec.getCompileOptions().setCompilerArgs(CollectionUtils.toStringList(spec.getCompileOptions().getCompilerArgs()));
    }

    private void logSourceFiles(JavaCompileSpec spec) {
        if (!spec.getCompileOptions().isListFiles()) {
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Source files to be compiled:");
        for (File file : spec.getSourceFiles()) {
            builder.append('\n');
            builder.append(file);
        }

        LOGGER.quiet(builder.toString());
    }

    private void logCompilerArguments(JavaCompileSpec spec) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }

        List<String> compilerArgs = new JavaCompilerArgumentsBuilder(spec).includeLauncherOptions(true).includeSourceFiles(true).build();
        String joinedArgs = Joiner.on(' ').join(compilerArgs);
        LOGGER.debug("Compiler arguments: {}", joinedArgs);
    }

    private WorkResult delegateAndHandleErrors(JavaCompileSpec spec) {
        try {
            return delegate.execute(spec);
        } catch (CompilationFailedException e) {
            if (spec.getCompileOptions().isFailOnError()) {
                throw e;
            }
            LOGGER.debug("Ignoring compilation failure.");
            return WorkResults.didWork(false);
        }
    }
}
