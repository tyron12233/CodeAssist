package com.tyron.builder.compiler;

import com.tyron.builder.compiler.incremental.java.IncrementalJavaTask;
import com.tyron.builder.compiler.java.CheckLibrariesTask;
import com.tyron.builder.compiler.java.JarTask;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;

import java.util.ArrayList;
import java.util.List;

public class JarBuilder extends BuilderImpl<JavaModule> {
    public JarBuilder(Project project, JavaModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public List<Task<? super JavaModule>> getTasks(BuildType type) {
        List<Task<? super JavaModule>> tasks = new ArrayList<>();
        tasks.add(new CheckLibrariesTask(getProject(), getModule(), getLogger()));
        tasks.add(new IncrementalJavaTask(getProject(), getModule(), getLogger()));
        tasks.add(new JarTask(getProject(), getModule(), getLogger()));
        return tasks;
    }

}
