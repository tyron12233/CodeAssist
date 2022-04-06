package com.tyron.builder.api;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.internal.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.project.taskfactory.IncrementalTaskAction;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.tasks.SourceTask;
import com.tyron.builder.api.tasks.TaskAction;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.tasks.compile.AbstractCompile;
import com.tyron.builder.api.tasks.incremental.IncrementalTaskInputs;
import com.tyron.builder.api.work.FileChange;
import com.tyron.builder.api.work.InputChanges;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("Convert2Lambda")
public class MockTasks extends TestTaskExecutionCase {

    public static class Aapt2Task extends SourceTask {

    }

    public static class JavaTask extends AbstractCompile {

    }

    @Override
    public void evaluateProject(BuildProject project) {
        TaskContainer tasks = project.getTasks();
        tasks.register("Aapt2Task", Aapt2Task.class, new Action<Aapt2Task>() {
            @Override
            public void execute(Aapt2Task aapt2Task) {

                aapt2Task.doLast(new Action<Task>() {
                    @Override
                    public void execute(Task task) {

                    }
                });
            }
        });

        tasks.register("JavaTask", JavaTask.class, new Action<JavaTask>() {
            @Override
            public void execute(JavaTask task) {
                task.dependsOn("Aapt2Task");

                File srcDir = project.mkdir(project.file("src"));;

                // represents all the java file under the src directory
                ConfigurableFileTree sources = project.fileTree(srcDir,
                        files -> files.include("**/*.java")
                );
                task.setClasspath(sources);
                task.getDestinationDirectory().dir(project.getBuildDir() + "/classes");

                task.doLast(new IncrementalTaskAction() {
                    @Override
                    public void execute(IncrementalTaskInputs inputs) {
                        System.out.println("Changed files: " + inputs);
                    }
                });
            }
        });
    }

    @Override
    public List<String> getTasksToExecute() {
        return ImmutableList.of("JavaTask");
    }
}
