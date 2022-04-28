package com.tyron.builder.compiler.java;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.internal.jar.JarArchive;
import com.tyron.builder.internal.jar.JarOptionsImpl;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.JavaModule;

import java.io.File;
import java.io.IOException;
import java.util.jar.Attributes;

public class JarTask extends Task<JavaModule> {

    private static final String TAG = JarTask.class.getSimpleName();

    public JarTask(Project project, JavaModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(BuildType type) throws IOException {

    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        JarArchive jarArchive = new JarArchive(false);
        jarArchive.setJarOptions(new JarOptionsImpl(new Attributes()));
        jarArchive.setOutputFile(new File(getModule().getBuildDirectory(), "bin/classes.jar"));
        jarArchive.createJarArchive(getModule());
    }
}
