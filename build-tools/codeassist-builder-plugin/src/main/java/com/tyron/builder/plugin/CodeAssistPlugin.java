package com.tyron.builder.plugin;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Plugin;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.compile.JavaCompile;
import com.tyron.builder.plugin.tasks.TransformAnnotationProcessorsTask;

import org.codehaus.groovy.reflection.android.AndroidSupport;

import java.io.File;

@SuppressWarnings("Convert2Lambda")
public class CodeAssistPlugin implements Plugin<Project> {

    private static final String TRANSFORM_ANNOTATION_PROCESSORS_TASK_NAME = "transformAnnotationProcessors";

    @Override
    public void apply(Project project) {
        boolean hasJavaPlugin = hasJavaPlugin(project);

        if (AndroidSupport.isRunningAndroid() && hasJavaPlugin) {
            registerTransformAnnotationProcessorsTask(project);
        }
    }

    private void registerTransformAnnotationProcessorsTask(Project project) {
        JavaCompile compileJava = (JavaCompile) project.getTasks().getByName("compileJava");
        project.getTasks().register(
                TRANSFORM_ANNOTATION_PROCESSORS_TASK_NAME,
                TransformAnnotationProcessorsTask.class,
                task -> {
            task.setSource(project.getConfigurations().getByName(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME).getAsFileTree());
        });
        compileJava.dependsOn(TRANSFORM_ANNOTATION_PROCESSORS_TASK_NAME);

        project.getTasks().register(
                "modifyAnnotationProcessorsPath", new Action<Task>() {
                    @Override
                    public void execute(Task task) {
                        task.getOutputs().upToDateWhen(Specs.SATISFIES_NONE);
                        task.mustRunAfter(TRANSFORM_ANNOTATION_PROCESSORS_TASK_NAME);

                        task.doLast(new Action<Task>() {
                            @Override
                            public void execute(Task task) {
                                Task transformTask = project.getTasks()
                                        .getByName(TRANSFORM_ANNOTATION_PROCESSORS_TASK_NAME);
                                FileCollection files = transformTask.getOutputs().getFiles();

                                File[] processors = files.getSingleFile().listFiles(c -> c.getName().endsWith(".jar"));
                                compileJava.getOptions().setAnnotationProcessorPath(
                                        processors == null
                                            ? null
                                            : project.files((Object[]) processors)
                                );
                            }
                        });
                    }
                }
        );
        compileJava.dependsOn("modifyAnnotationProcessorsPath");
    }

    private boolean hasJavaPlugin(Project project) {
        return project.getPlugins().hasPlugin("java");
    }
}