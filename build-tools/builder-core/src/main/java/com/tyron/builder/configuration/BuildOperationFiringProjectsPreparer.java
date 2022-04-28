package com.tyron.builder.configuration;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.operations.BuildOperationCategory;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.RunnableBuildOperation;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectRegistry;
import com.tyron.builder.initialization.ConfigureBuildBuildOperationType;

public class BuildOperationFiringProjectsPreparer implements ProjectsPreparer {
    private static final ConfigureBuildBuildOperationType.Result CONFIGURE_BUILD_RESULT = new ConfigureBuildBuildOperationType.Result() {
    };
    private final ProjectsPreparer delegate;
    private final BuildOperationExecutor buildOperationExecutor;

    public BuildOperationFiringProjectsPreparer(ProjectsPreparer delegate, BuildOperationExecutor buildOperationExecutor) {
        this.delegate = delegate;
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public void prepareProjects(GradleInternal gradle) {
        buildOperationExecutor.run(new ConfigureBuild(gradle));
    }

    private class ConfigureBuild implements RunnableBuildOperation {
        private final GradleInternal gradle;

        public ConfigureBuild(GradleInternal gradle) {
            this.gradle = gradle;
        }

        @Override
        public void run(BuildOperationContext context) {
            delegate.prepareProjects(gradle);
            context.setResult(CONFIGURE_BUILD_RESULT);
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            BuildOperationDescriptor.Builder builder = BuildOperationDescriptor.displayName(gradle.contextualize("Configure build"));
            if (gradle.isRootBuild()) {
                builder.metadata(BuildOperationCategory.CONFIGURE_ROOT_BUILD);
            } else {
                builder.metadata(BuildOperationCategory.CONFIGURE_BUILD);
            }
            builder.totalProgress(gradle.getSettings().getProjectRegistry().size());
            //noinspection Convert2Lambda
            return builder.details(new ConfigureBuildBuildOperationType.Details() {
                @Override
                public String getBuildPath() {
                    return gradle.getIdentityPath().toString();
                }
            });
        }
    }
}
