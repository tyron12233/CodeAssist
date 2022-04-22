package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.RunnableBuildOperation;
import com.tyron.builder.internal.build.PublicBuildPath;

import javax.annotation.Nullable;

public class BuildOperationFiringSettingsPreparer implements SettingsPreparer {
    private static final LoadBuildBuildOperationType.Result RESULT = new LoadBuildBuildOperationType.Result() {
    };

    private final SettingsPreparer delegate;
    private final BuildOperationExecutor buildOperationExecutor;
    @Nullable
    private final PublicBuildPath fromBuild;

    public BuildOperationFiringSettingsPreparer(SettingsPreparer delegate, BuildOperationExecutor buildOperationExecutor, @Nullable PublicBuildPath fromBuild) {
        this.delegate = delegate;
        this.buildOperationExecutor = buildOperationExecutor;
        this.fromBuild = fromBuild;
    }

    @Override
    public void prepareSettings(GradleInternal gradle) {
        buildOperationExecutor.run(new LoadBuild(gradle));
    }

    private class LoadBuild implements RunnableBuildOperation {
        private final GradleInternal gradle;

        public LoadBuild(GradleInternal gradle) {
            this.gradle = gradle;
        }

        @Override
        public void run(BuildOperationContext context) {
            doLoadBuild();
            context.setResult(RESULT);
        }

        void doLoadBuild() {
            delegate.prepareSettings(gradle);
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor.displayName(gradle.contextualize("Load build"))
                    .details(new LoadBuildBuildOperationType.Details() {
                        @Override
                        public String getBuildPath() {
                            return gradle.getIdentityPath().toString();
                        }

                        @Override
                        public String getIncludedBy() {
                            return fromBuild == null ? null : fromBuild.getBuildPath().toString();
                        }
                    });
        }
    }

}