package org.gradle.api;

import com.google.common.collect.ImmutableList;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.project.taskfactory.IncrementalTaskAction;
import org.gradle.api.BuildProject;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.SourceTask;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.compile.AbstractCompile;
import org.gradle.work.FileChange;
import org.gradle.work.InputChanges;
import org.gradle.work.NormalizeLineEndings;

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
