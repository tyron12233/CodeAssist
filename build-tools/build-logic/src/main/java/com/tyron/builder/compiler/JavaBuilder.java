package com.tyron.builder.compiler;

import com.tyron.builder.compiler.dex.JavaD8Task;
import com.tyron.builder.compiler.incremental.java.IncrementalJavaTask;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.api.JavaModule;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a java project and dexes the class files
 */
public class JavaBuilder extends BuilderImpl<JavaModule> {

    public JavaBuilder(JavaModule project, ILogger logger) {
        super(project, logger);
    }

    @Override
    public List<Task<? super JavaModule>> getTasks(BuildType type) {
        List<Task<? super JavaModule>> tasks = new ArrayList<>();
        tasks.add(new IncrementalJavaTask(getModule(), getLogger()));
        tasks.add(new JavaD8Task(getModule(), getLogger()));
        return tasks;
    }
}
