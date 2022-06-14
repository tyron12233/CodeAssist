package com.tyron.builder.plugin;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.plugins.JavaPlugin;
import com.tyron.builder.api.specs.Specs;
import com.tyron.builder.api.tasks.compile.JavaCompile;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.ProjectBuilder;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.plugin.tasks.TransformAnnotationProcessorsTask;
import com.tyron.builder.project.Project;
import com.tyron.builder.util.GUtil;

import org.codehaus.groovy.reflection.android.AndroidSupport;

import java.io.File;
import java.util.Locale;

@SuppressWarnings("Convert2Lambda")
public class CodeAssistPlugin implements Plugin<BuildProject> {

    private static final String TRANSFORM_ANNOTATION_PROCESSORS_TASK_NAME = "transformAnnotationProcessors";

    @Override
    public void apply(BuildProject project) {
        boolean hasJavaPlugin = hasJavaPlugin(project);

        if (AndroidSupport.isRunningAndroid() && hasJavaPlugin) {
            registerTransformAnnotationProcessorsTask(project);
        }
    }

    private void registerTransformAnnotationProcessorsTask(BuildProject project) {
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

    private boolean hasJavaPlugin(BuildProject project) {
        return project.getPlugins().hasPlugin("java");
    }

    private static class CodeAssistLoggerDelegate implements ILogger {

        private final Logger delegate;

        private CodeAssistLoggerDelegate(Logger logger) {
            this.delegate = logger;
        }

        @Override
        public void info(DiagnosticWrapper wrapper) {
            delegate.info(wrapper.getMessage(Locale.ENGLISH));
        }

        @Override
        public void debug(DiagnosticWrapper wrapper) {
            delegate.debug(wrapper.getMessage(Locale.ENGLISH));
        }

        @Override
        public void warning(DiagnosticWrapper wrapper) {
            delegate.warn(wrapper.getMessage(Locale.ENGLISH));
        }

        @Override
        public void error(DiagnosticWrapper wrapper) {
            delegate.error(wrapper.getMessage(Locale.ENGLISH));
        }
    }
}