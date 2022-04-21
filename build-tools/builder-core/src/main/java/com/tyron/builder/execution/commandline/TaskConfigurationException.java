package com.tyron.builder.execution.commandline;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.internal.exceptions.Contextual;
import com.tyron.builder.api.internal.exceptions.FailureResolutionAware;
import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.cli.CommandLineArgumentException;

@Contextual
public class TaskConfigurationException extends BuildException implements FailureResolutionAware {

    private final String taskPath;

    public TaskConfigurationException(String taskPath, String message, Exception cause) {
        super(message, cause);
        this.taskPath = taskPath;
    }

    @Override
    public void appendResolutions(Context context) {
        context.appendResolution(output -> {
            output.text("Run ");
            context.getClientMetaData().describeCommand(output.withStyle(StyledTextOutput.Style.UserInput), ProjectInternal.HELP_TASK);
            output.withStyle(StyledTextOutput.Style.UserInput).format(" --task %s", taskPath);
            output.text(" to get task usage details.");
        });
    }
}
