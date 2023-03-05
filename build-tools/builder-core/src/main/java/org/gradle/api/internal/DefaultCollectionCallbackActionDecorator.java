package org.gradle.api.internal;

import org.gradle.api.Action;
import org.gradle.configuration.internal.UserCodeApplicationContext;
import org.gradle.configuration.internal.UserCodeApplicationId;
import org.gradle.internal.InternalListener;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.RunnableBuildOperation;

import java.util.function.Predicate;

import javax.annotation.Nullable;

public class DefaultCollectionCallbackActionDecorator implements CollectionCallbackActionDecorator {
    private final BuildOperationExecutor buildOperationExecutor;
    private final UserCodeApplicationContext userCodeApplicationContext;

    public DefaultCollectionCallbackActionDecorator(BuildOperationExecutor buildOperationExecutor, UserCodeApplicationContext userCodeApplicationContext) {
        this.buildOperationExecutor = buildOperationExecutor;
        this.userCodeApplicationContext = userCodeApplicationContext;
    }

    @Override
    public <T> Action<T> decorate(@Nullable Action<T> action) {
        if (action == null || action instanceof InternalListener) {
            return action;
        }

        UserCodeApplicationContext.Application application = userCodeApplicationContext.current();
        if (application == null) {
            return action;
        }
        return new BuildOperationEmittingAction<>(application.getId(), application.reapplyLater(action));
    }

    @Override
    public <T> Predicate<T> decorateSpec(Predicate<T> spec) {
        UserCodeApplicationContext.Application application = userCodeApplicationContext.current();
        if (application == null) {
            return spec;
        }
        return new Predicate<T>() {
            @Override
            public boolean test(T element) {
                return application.reapply(() -> spec.test(element));
            }
        };
    }

    private static abstract class Operation implements RunnableBuildOperation {

        private final UserCodeApplicationId applicationId;

        Operation(UserCodeApplicationId applicationId) {
            this.applicationId = applicationId;
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor
                .displayName("Execute container callback action")
                .details(new OperationDetails(applicationId));
        }
    }

    private static class OperationDetails implements ExecuteDomainObjectCollectionCallbackBuildOperationType.Details {
        private final UserCodeApplicationId applicationId;

        OperationDetails(UserCodeApplicationId applicationId) {
            this.applicationId = applicationId;
        }

        @Override
        public long getApplicationId() {
            return applicationId.longValue();
        }
    }

    private class BuildOperationEmittingAction<T> implements Action<T> {
        private final UserCodeApplicationId applicationId;
        private final Action<T> delegate;

        BuildOperationEmittingAction(UserCodeApplicationId applicationId, Action<T> delegate) {
            this.applicationId = applicationId;
            this.delegate = delegate;
        }

        @Override
        public void execute(final T arg) {
            buildOperationExecutor.run(new Operation(applicationId) {
                @Override
                public void run(final BuildOperationContext context) {
                    delegate.execute(arg);
                    context.setResult(ExecuteDomainObjectCollectionCallbackBuildOperationType.RESULT);
                }
            });
        }
    }

}
