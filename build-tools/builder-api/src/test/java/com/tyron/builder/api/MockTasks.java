package com.tyron.builder.api;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.internal.project.taskfactory.IncrementalTaskAction;
import com.tyron.builder.api.BuildProject;
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
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.tasks.compile.AbstractCompile;
import com.tyron.builder.work.FileChange;
import com.tyron.builder.work.InputChanges;
import com.tyron.builder.work.NormalizeLineEndings;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Mocks the execution of building an APK which test tasks of their ability to compile
 * only changed inputs
 */
@SuppressWarnings("Convert2Lambda")
public class MockTasks extends BaseProjectTestCase {

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
            ImmutableList<FileChange> changedFiles = ImmutableList.copyOf(inputs.getFileChanges(getStableSources()));
            System.out.println("Compiling changed files: " + changedFiles);
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
    public void configure(BuildProject project) {
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

                // represents all the java file under the javaDir
                ConfigurableFileTree sources = project.fileTree(javaDir,
                        files -> files.include("**/*.java")
                );
                task.setSource(sources);

                task.getDestinationDirectory().dir(project.getBuildDir() + "/classes");

                task.setSourceCompatibility("1.8");
                task.setTargetCompatibility("1.8");
            }
        });

        tasks.register("assembleTask").configure(assembly -> {
            // ideally this would depend on the last task that will assemble the APK,
            // for this test the last task is JavaTask
            assembly.dependsOn("JavaTask");
        });
    }

    @Override
    public List<String> getTasks() {
        return ImmutableList.of("assembleTask");
    }
}
