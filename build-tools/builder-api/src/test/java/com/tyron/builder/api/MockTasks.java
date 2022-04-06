package com.tyron.builder.api;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.internal.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.fingerprint.RelativePathInputNormalizer;
import com.tyron.builder.api.internal.project.taskfactory.IncrementalTaskAction;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.tasks.CacheableTask;
import com.tyron.builder.api.tasks.CompileClasspath;
import com.tyron.builder.api.tasks.IgnoreEmptyDirectories;
import com.tyron.builder.api.tasks.InputFiles;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.OutputDirectory;
import com.tyron.builder.api.tasks.PathSensitive;
import com.tyron.builder.api.tasks.PathSensitivity;
import com.tyron.builder.api.tasks.SkipWhenEmpty;
import com.tyron.builder.api.tasks.SourceTask;
import com.tyron.builder.api.tasks.TaskAction;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.tasks.compile.AbstractCompile;
import com.tyron.builder.api.tasks.incremental.IncrementalTaskInputs;
import com.tyron.builder.api.work.FileChange;
import com.tyron.builder.api.work.Incremental;
import com.tyron.builder.api.work.InputChanges;
import com.tyron.builder.api.work.NormalizeLineEndings;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@SuppressWarnings("Convert2Lambda")
public class MockTasks extends TestTaskExecutionCase {

    public static class Aapt2Task extends SourceTask {

        private File outputDirectory;

        public Aapt2Task() {
            doLast(new IncrementalTaskAction() {
                @Override
                public void execute(@Nullable InputChanges inputs) {
                    compile(inputs);
                }
            });
        }

        public void compile(InputChanges inputs) {
            System.out.println("Compiling resource files");
        }

        @OutputDirectory
        public File getOutputDirectory() {
            return outputDirectory;
        }

        public void setOutputDirectory(File directory) {
            this.outputDirectory = directory;
        }
    }

    @CacheableTask
    public static class JavaTask extends AbstractCompile {

        private final FileCollection stableSources = getProject().files((Callable<FileTree>) this::getSource);

        public JavaTask() {
            doLast(new IncrementalTaskAction() {
                @Override
                public void execute(@Nullable InputChanges inputs) {
                    compile(inputs);
                }
            });
        }

        public void compile(InputChanges inputs) {
            ConfigurableFileCollection files = getProject().getObjects().fileCollection();
            System.out.println("Input changes: " + inputs);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @Internal("tracked via stableSources")
        public FileTree getSource() {
            return super.getSource();
        }

        @Override
        @CompileClasspath
        @Incremental
        public FileCollection getClasspath() {
            return super.getClasspath();
        }

        /**
         * The sources for incremental change detection.
         *
         * @since 6.0
         */
        @SkipWhenEmpty
        @IgnoreEmptyDirectories
        @NormalizeLineEndings
        @PathSensitive(PathSensitivity.RELATIVE)
        @InputFiles
        protected FileCollection getStableSources() {
            return stableSources;
        }
    }

    @Override
    public void evaluateProject(BuildProject project) {
        TaskContainer tasks = project.getTasks();
        tasks.register("Aapt2Task", Aapt2Task.class, new Action<Aapt2Task>() {
            @Override
            public void execute(Aapt2Task aapt2Task) {

                File resDir = project.mkdir(project.file("src/main/res"));
                ConfigurableFileTree resFiles = project.fileTree(resDir);
                aapt2Task.setSource(resFiles);

            }
        });

        tasks.register("JavaTask", JavaTask.class, new Action<JavaTask>() {
            @Override
            public void execute(JavaTask task) {
                task.dependsOn("Aapt2Task");

                File javaDir = project.mkdir(project.file("src/main/java"));;

                // represents all the java file under the src directory
                ConfigurableFileTree sources = project.fileTree(javaDir,
                        files -> files.include("**/*.java")
                );
                task.setSource(sources);
                task.getDestinationDirectory().dir(project.getBuildDir() + "/classes");

                task.setSourceCompatibility("1.8");
                task.setTargetCompatibility("1.8");
            }
        });
    }

    @Override
    public List<String> getTasksToExecute() {
        return ImmutableList.of("JavaTask");
    }
}