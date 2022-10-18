package org.gradle.tooling.internal.provider;

import org.gradle.StartParameter;
import org.gradle.initialization.BuildRequestContext;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.launcher.exec.BuildActionExecuter;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.launcher.exec.BuildActionResult;
import org.gradle.launcher.exec.BuildExecuter;

/**
 * Sets up logging around a session.
 */
public class SetupLoggingActionExecuter implements BuildExecuter {
    private final BuildActionExecuter<BuildActionParameters, BuildRequestContext> delegate;
    private final LoggingManagerInternal loggingManager;

    public SetupLoggingActionExecuter(LoggingManagerInternal loggingManager, BuildActionExecuter<BuildActionParameters, BuildRequestContext> delegate) {
        this.loggingManager = loggingManager;
        this.delegate = delegate;
    }

    @Override
    public BuildActionResult execute(BuildAction action, BuildActionParameters actionParameters, BuildRequestContext requestContext) {
        StartParameter startParameter = action.getStartParameter();
        loggingManager.setLevelInternal(startParameter.getLogLevel());
        loggingManager.enableUserStandardOutputListeners();
        loggingManager.start();
        try {
            return delegate.execute(action, actionParameters, requestContext);
        } finally {
            loggingManager.stop();
        }
    }
}
