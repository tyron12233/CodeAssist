package com.tyron.builder.plugin;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.ProjectBuilder;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.util.GUtil;

import java.util.Locale;

@SuppressWarnings("Convert2Lambda")
public class CodeAssistPlugin implements Plugin<BuildProject> {

    private static final String TASK_NAME = "codeAssistAssembleTask";

    @Override
    public void apply(BuildProject project) {
        CodeAssistPluginExtension extension =
                project.getExtensions().create("codeAssist", CodeAssistPluginExtension.class);
        RepositoryExtension repositoryExtension = new RepositoryExtension() {
            @Override
            public void maven() {
                System.out.println("Maven called");
            }
        };
        project.getExtensions().add("codeAssistRepositories", repositoryExtension);

        project.getTasks().register(TASK_NAME, new Action<Task>() {
            @Override
            public void execute(Task task) {
                task.doLast(new Action<Task>() {
                    @Override
                    public void execute(Task task) {
                        GUtil.unchecked(() -> {
                            Project p = new Project(project.getRootDir());
                            p.open();
                            p.index();

                            ILogger logger = new CodeAssistLoggerDelegate(task.getLogger());
                            ProjectBuilder projectBuilder = new ProjectBuilder(p, logger);
                            projectBuilder.build(BuildType.DEBUG);
                        });
                    }
                });
            }
        });
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