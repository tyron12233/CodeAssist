package com.tyron.code.completion;

import com.tyron.code.compiler.AAPT2Compiler;
import com.tyron.code.model.Project;
import com.tyron.code.util.exception.CompilationFailedException;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main entry point for building apk files
 */
public class MainCompiler {

    private final ExecutorService mService = Executors.newFixedThreadPool(1);

    private final Project mProject;

    public MainCompiler(Project project) {
        mProject = project;
    }

    public void compile() throws IOException, CompilationFailedException {
        AAPT2Compiler compiler = new AAPT2Compiler(mProject);
        compiler.run();
    }
}
