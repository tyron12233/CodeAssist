package com.tyron.builder.plugin;

import com.tyron.builder.BuildAdapter;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.DefaultTask;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.invocation.Gradle;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.ProjectBuilder;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.util.GUtil;

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

                            ProjectBuilder projectBuilder = new ProjectBuilder(p, ILogger.EMPTY);
                            projectBuilder.build(BuildType.DEBUG);
                        });
                    }
                });
            }
        });
    }
}