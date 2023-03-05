package org.gradle.execution.commandline;

import com.google.common.collect.Lists;
import org.gradle.TaskExecutionRequest;
import org.gradle.api.Task;
import org.gradle.execution.TaskSelection;
import org.gradle.execution.TaskSelector;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class CommandLineTaskParser {
    private final CommandLineTaskConfigurer taskConfigurer;
    private final TaskSelector taskSelector;

    public CommandLineTaskParser(CommandLineTaskConfigurer commandLineTaskConfigurer, TaskSelector taskSelector) {
        this.taskConfigurer = commandLineTaskConfigurer;
        this.taskSelector = taskSelector;
    }

    public List<TaskSelection> parseTasks(TaskExecutionRequest taskExecutionRequest) {
        List<TaskSelection> out = Lists.newArrayList();
        List<String> remainingPaths = new LinkedList<String>(taskExecutionRequest.getArgs());
        while (!remainingPaths.isEmpty()) {
            String path = remainingPaths.remove(0);
            TaskSelection selection = taskSelector.getSelection(taskExecutionRequest.getProjectPath(), taskExecutionRequest.getRootDir(), path);
            Set<Task> tasks = selection.getTasks();
            remainingPaths = taskConfigurer.configureTasks(tasks, remainingPaths);
            out.add(selection);
        }
        return out;
    }
}
