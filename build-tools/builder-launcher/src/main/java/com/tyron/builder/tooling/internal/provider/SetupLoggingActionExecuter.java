package com.tyron.builder.tooling.internal.provider;

import com.tyron.builder.StartParameter;
import com.tyron.builder.initialization.BuildRequestContext;
import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.launcher.exec.BuildActionExecuter;
import com.tyron.builder.launcher.exec.BuildActionParameters;
import com.tyron.builder.launcher.exec.BuildActionResult;
import com.tyron.builder.launcher.exec.BuildExecuter;

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
