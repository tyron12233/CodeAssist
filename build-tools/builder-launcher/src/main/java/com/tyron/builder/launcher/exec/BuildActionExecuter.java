package com.tyron.builder.launcher.exec;

import com.tyron.builder.internal.invocation.BuildAction;

public interface BuildActionExecuter<PARAMS, CONTEXT> {
    /**
     * Executes the given action, and returns the result. Build failures should be packaged in the result, rather than thrown. A failure packaged in this way will have already been reported as a build failure and should not be reported again.
     *
     * @param action The action
     * @return The result.
     */
    BuildActionResult execute(BuildAction action, PARAMS actionParameters, CONTEXT context);
}
