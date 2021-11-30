package com.tyron.builder.compiler;

import com.tyron.builder.compiler.dex.JavaD8Task;
import com.tyron.builder.compiler.incremental.dex.IncrementalD8Task;
import com.tyron.builder.compiler.incremental.java.IncrementalJavaTask;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.api.JavaProject;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a java project and dexes the class files
 */
public class JavaBuilder extends BuilderImpl<JavaProject> {

    public JavaBuilder(JavaProject project, ILogger logger) {
        super(project, logger);
    }

    @Override
    public List<Task<JavaProject>> getTasks() {
        List<Task<JavaProject>> tasks = new ArrayList<>();
        tasks.add(new IncrementalJavaTask(getProject(), getLogger()));
        tasks.add(new JavaD8Task(getProject(), getLogger()));
        return tasks;
    }
}
