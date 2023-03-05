package org.gradle.execution.commandline;

import org.gradle.api.GradleException;
import org.gradle.internal.exceptions.Contextual;
import org.gradle.api.internal.exceptions.FailureResolutionAware;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.api.internal.project.ProjectInternal;

@Contextual
public class TaskConfigurationException extends GradleException implements FailureResolutionAware {

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
