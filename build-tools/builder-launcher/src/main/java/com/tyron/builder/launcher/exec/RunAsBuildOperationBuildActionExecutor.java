package com.tyron.builder.launcher.exec;

import com.tyron.builder.internal.buildtree.BuildActionRunner;
import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.CallableBuildOperation;
import com.tyron.builder.internal.operations.logging.LoggingBuildOperationProgressBroadcaster;
import com.tyron.builder.internal.operations.notify.BuildOperationNotificationValve;
import com.tyron.builder.internal.session.BuildSessionActionExecutor;
import com.tyron.builder.internal.session.BuildSessionContext;

/**
 * An {@link BuildActionRunner} that wraps all work in a build operation.
 */
public class RunAsBuildOperationBuildActionExecutor implements BuildSessionActionExecutor {
    private static final RunBuildBuildOperationType.Details DETAILS = new RunBuildBuildOperationType.Details() {
    };
    private static final RunBuildBuildOperationType.Result RESULT = new RunBuildBuildOperationType.Result() {
    };
    private final BuildSessionActionExecutor delegate;
    private final BuildOperationExecutor buildOperationExecutor;
    private final LoggingBuildOperationProgressBroadcaster loggingBuildOperationProgressBroadcaster;
    private final BuildOperationNotificationValve buildOperationNotificationValve;

    public RunAsBuildOperationBuildActionExecutor(BuildSessionActionExecutor delegate,
                                                  BuildOperationExecutor buildOperationExecutor,
                                                  LoggingBuildOperationProgressBroadcaster loggingBuildOperationProgressBroadcaster,
                                                  BuildOperationNotificationValve buildOperationNotificationValve) {
        this.delegate = delegate;
        this.buildOperationExecutor = buildOperationExecutor;
        this.loggingBuildOperationProgressBroadcaster = loggingBuildOperationProgressBroadcaster;
        this.buildOperationNotificationValve = buildOperationNotificationValve;
    }

    @Override
    public BuildActionRunner.Result execute(BuildAction action, BuildSessionContext context) {
        buildOperationNotificationValve.start();
        try {
            return buildOperationExecutor.call(new CallableBuildOperation<BuildActionRunner.Result>() {
                @Override
                public BuildActionRunner.Result call(BuildOperationContext buildOperationContext) {
                    loggingBuildOperationProgressBroadcaster.rootBuildOperationStarted();
                    BuildActionRunner.Result result = delegate.execute(action, context);
                    buildOperationContext.setResult(RESULT);
                    if (result.getBuildFailure() != null) {
                        buildOperationContext.failed(result.getBuildFailure());
                    }
                    return result;
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    return BuildOperationDescriptor.displayName("Run build").details(DETAILS);
                }
            });
        } finally {
            buildOperationNotificationValve.stop();
        }
    }
}
