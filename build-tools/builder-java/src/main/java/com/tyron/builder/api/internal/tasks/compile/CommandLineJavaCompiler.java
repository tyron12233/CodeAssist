package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.WorkResults;
import com.tyron.builder.internal.jvm.Jvm;
import com.tyron.builder.language.base.internal.compile.Compiler;
import com.tyron.builder.process.ExecResult;
import com.tyron.builder.process.internal.ExecHandle;
import com.tyron.builder.process.internal.ExecHandleBuilder;
import com.tyron.builder.process.internal.ExecHandleFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

/**
 * Executes the Java command line compiler specified in {@code JavaCompileSpec.forkOptions.getExecutable()}.
 */
public class CommandLineJavaCompiler implements Compiler<JavaCompileSpec>, Serializable {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandLineJavaCompiler.class);

    private final CompileSpecToArguments<JavaCompileSpec> argumentsGenerator = new CommandLineJavaCompilerArgumentsGenerator();
    private final ExecHandleFactory execHandleFactory;

    public CommandLineJavaCompiler(ExecHandleFactory execHandleFactory) {
        this.execHandleFactory = execHandleFactory;
    }

    @Override
    public WorkResult execute(JavaCompileSpec spec) {
        final MinimalJavaCompilerDaemonForkOptions forkOptions = spec.getCompileOptions().getForkOptions();
        String executable = forkOptions.getJavaHome() != null ? Jvm.forHome(forkOptions.getJavaHome()).getJavacExecutable().getAbsolutePath() : forkOptions.getExecutable();
        LOGGER.info("Compiling with Java command line compiler '{}'.", executable);

        ExecHandle handle = createCompilerHandle(executable, spec);
        executeCompiler(handle);

        return WorkResults.didWork(true);
    }

    private ExecHandle createCompilerHandle(String executable, JavaCompileSpec spec) {
        ExecHandleBuilder builder = execHandleFactory.newExec();
        builder.setWorkingDir(spec.getWorkingDir());
        builder.setExecutable(executable);
        argumentsGenerator.collectArguments(spec, new ExecSpecBackedArgCollector(builder));
        builder.setIgnoreExitValue(true);
        return builder.build();
    }

    private void executeCompiler(ExecHandle handle) {
        handle.start();
        ExecResult result = handle.waitForFinish();
        if (result.getExitValue() != 0) {
            throw new CompilationFailedException(result.getExitValue());
        }
    }
}
