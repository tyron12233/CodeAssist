package com.tyron.builder.execution;

import com.tyron.builder.TaskExecutionRequest;
import com.tyron.builder.StartParameter;
import com.tyron.builder.execution.ProjectConfigurer;
import com.tyron.builder.util.GUtil;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.configuration.project.BuiltInCommand;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * A {@link BuildConfigurationAction} that selects the default tasks for a project, or if none are defined, the 'help' task.
 */
public class DefaultTasksBuildExecutionAction implements BuildConfigurationAction {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTasksBuildExecutionAction.class);
    private final ProjectConfigurer projectConfigurer;
    private final List<BuiltInCommand> builtInCommands;

    public DefaultTasksBuildExecutionAction(ProjectConfigurer projectConfigurer, List<BuiltInCommand> builtInCommands) {
        this.projectConfigurer = projectConfigurer;
        this.builtInCommands = builtInCommands;
    }

    @Override
    public void configure(BuildExecutionContext context) {
        StartParameter startParameter = context.getGradle().getStartParameter();

        for (TaskExecutionRequest request : startParameter.getTaskRequests()) {
            if (!request.getArgs().isEmpty()) {
                context.proceed();
                return;
            }
        }

        // Gather the default tasks from this first group project
        ProjectInternal project = context.getGradle().getDefaultProject();

        //so that we don't miss out default tasks
        projectConfigurer.configure(project);

        List<String> defaultTasks = project.getDefaultTasks();
        if (defaultTasks.size() == 0) {
            defaultTasks = new ArrayList<>();
            for (BuiltInCommand command : builtInCommands) {
                defaultTasks.addAll(command.asDefaultTask());
            }
            LOGGER.info("No tasks specified. Using default task {}", GUtil.toString(defaultTasks));
        } else {
            LOGGER.info("No tasks specified. Using project default tasks {}", GUtil.toString(defaultTasks));
        }

        startParameter.setTaskNames(defaultTasks);
        context.proceed();
    }
}
