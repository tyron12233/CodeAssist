package com.tyron.builder.configuration.internal;

import com.tyron.builder.configuration.project.NotifyProjectBeforeEvaluatedBuildOperationType;
import com.tyron.builder.internal.operations.BuildOperationType;

/**
 * Execution of a lifecycle listener/callback.
 *
 * Expected to be the child operation of an operation indicating the lifecycle event (e.g. {@link NotifyProjectBeforeEvaluatedBuildOperationType}).
 *
 * @since 4.10
 */
public final class ExecuteListenerBuildOperationType implements BuildOperationType<ExecuteListenerBuildOperationType.Details, ExecuteListenerBuildOperationType.Result> {

    public interface Details {

        /**
         * The application ID of the script or plugin that registered the listener.
         *
         * @see org.gradle.api.internal.plugins.ApplyPluginBuildOperationType.Details#getApplicationId()
         * @see org.gradle.configuration.ApplyScriptPluginBuildOperationType.Details#getApplicationId()
         */
        long getApplicationId();

        /**
         * A human friendly description of where the listener was registered by the user.
         *
         * General contract is public-type-simplename.method-name.
         * e.g. Project.beforeEvaluate
         */
        String getRegistrationPoint();
    }

    public interface Result {
    }

    static final Result RESULT = new Result() {
    };

    private ExecuteListenerBuildOperationType() {
    }
}
