package com.tyron.builder.execution;

import com.tyron.builder.TaskExecutionRequest;
import com.tyron.builder.execution.TaskSelection;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.execution.commandline.CommandLineTaskParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * A {@link BuildConfigurationAction} which selects tasks which match the provided names. For each name, selects all tasks in all
 * projects whose name is the given name.
 */
public class TaskNameResolvingBuildConfigurationAction implements BuildConfigurationAction {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskNameResolvingBuildConfigurationAction.class);
    private final CommandLineTaskParser commandLineTaskParser;

    public TaskNameResolvingBuildConfigurationAction(CommandLineTaskParser commandLineTaskParser) {
        this.commandLineTaskParser = commandLineTaskParser;
    }

    @Override
    public void configure(BuildExecutionContext context) {
        GradleInternal gradle = context.getGradle();

        List<TaskExecutionRequest> taskParameters = gradle.getStartParameter().getTaskRequests();
        for (TaskExecutionRequest taskParameter : taskParameters) {
            List<TaskSelection> taskSelections = commandLineTaskParser.parseTasks(taskParameter);
            for (TaskSelection taskSelection : taskSelections) {
                LOGGER.info("Selected primary task '{}' from project {}", taskSelection.getTaskName(), taskSelection.getProjectPath());
                context.getExecutionPlan().addEntryTasks(taskSelection.getTasks());
            }
        }

        context.proceed();
    }

}
