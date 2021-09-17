package com.tyron.builder.compiler.incremental.kotlin;

import com.tyron.builder.BuildModule;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Project;

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.config.Services;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class IncrementalKotlinCompiler extends Task {

    private static final String TAG = IncrementalKotlinCompiler.class.getSimpleName();

    private File mKotlinHome;
    private File mClassOutput;
    private Project mProject;
    private List<File> mFilesToCompile;
    private ILogger mLogger;

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(Project project, ILogger logger) throws IOException {
        mProject = project;
        mLogger = logger;
        mFilesToCompile = new ArrayList<>(getSourceFiles(project.getJavaDirectory()));

        mKotlinHome = new File(BuildModule.getContext().getFilesDir(), "kotlin-home");
        if (!mKotlinHome.exists() && !mKotlinHome.mkdirs()) {
            throw new IOException("Unable to create kotlin home directory");
        }

        mClassOutput = new File(project.getBuildDirectory(), "bin/classes");
        if (!mClassOutput.exists() && !mClassOutput.mkdirs()) {
            throw new IOException("Unable to create class output directory");
        }
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        if (mFilesToCompile.isEmpty()) {
            mLogger.info("No kotlin source files, Skipping compilation.");
            return;
        }

        List<String> arguments = new ArrayList<>();
        Collections.addAll(arguments, "-cp", mProject.getLibraries().stream().map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator)));
        Collections.addAll(arguments, mFilesToCompile.stream().map(File::getAbsolutePath).toArray(String[]::new));

        try {
            K2JVMCompiler compiler = new K2JVMCompiler();
            K2JVMCompilerArguments args = new K2JVMCompilerArguments();
            compiler.parseArguments(arguments.toArray(new String[0]), args);

            args.setCompileJava(false);
            args.setIncludeRuntime(false);
            args.setNoJdk(true);
            args.setNoReflect(true);
            args.setNoStdlib(true);
            args.setKotlinHome(mKotlinHome.getAbsolutePath());
            args.setDestination(mClassOutput.getAbsolutePath());
            compiler.exec(new Collector(), Services.EMPTY, args);
        } catch (Exception e) {
            throw new CompilationFailedException(e);
        }
    }

    private class Collector implements MessageCollector {

        @Override
        public void clear() {

        }

        @Override
        public boolean hasErrors() {
            return false;
        }

        @Override
        public void report(CompilerMessageSeverity severity, String s, CompilerMessageSourceLocation compilerMessageSourceLocation) {
            switch (severity) {
                case ERROR: mLogger.error(s); break;
                case STRONG_WARNING:
                case WARNING: mLogger.warning(s); break;
                case INFO: mLogger.info(s); break;
                default: mLogger.debug(s);
            }
        }
    }

    private List<File> getSourceFiles(File dir) {
        List<File> files = new ArrayList<>();

        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    files.addAll(getSourceFiles(child));
                } else {
                    if (child.getName().endsWith(".kt")) {
                        files.add(child);
                    }
                }
            }
        }

        return files;
    }
}
