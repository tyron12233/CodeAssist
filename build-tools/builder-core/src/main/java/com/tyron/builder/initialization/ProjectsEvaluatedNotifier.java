package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.RunnableBuildOperation;

public class ProjectsEvaluatedNotifier {
    private static final NotifyProjectsEvaluatedBuildOperationType.Result PROJECTS_EVALUATED_RESULT = new NotifyProjectsEvaluatedBuildOperationType.Result() {
    };
    private final BuildOperationExecutor buildOperationExecutor;

    public ProjectsEvaluatedNotifier(BuildOperationExecutor buildOperationExecutor) {
        this.buildOperationExecutor = buildOperationExecutor;
    }

    public void notify(GradleInternal gradle) {
        buildOperationExecutor.run(new NotifyProjectsEvaluatedListeners(gradle));
    }

    private static class NotifyProjectsEvaluatedListeners implements RunnableBuildOperation {
        private final GradleInternal gradle;

        public NotifyProjectsEvaluatedListeners(GradleInternal gradle) {
            this.gradle = gradle;
        }

        @Override
        public void run(BuildOperationContext context) {
            gradle.getBuildListenerBroadcaster().projectsEvaluated(gradle);
            context.setResult(PROJECTS_EVALUATED_RESULT);
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor.displayName(gradle.contextualize("Notify projectsEvaluated listeners"))
                    .details(new NotifyProjectsEvaluatedBuildOperationType.Details() {
                        @Override
                        public String getBuildPath() {
                            return gradle.getIdentityPath().toString();
                        }
                    });
        }
    }
}