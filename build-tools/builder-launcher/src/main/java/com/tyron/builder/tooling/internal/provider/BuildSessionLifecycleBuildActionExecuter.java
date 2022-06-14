package com.tyron.builder.tooling.internal.provider;

import com.google.common.base.Function;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.initialization.BuildRequestContext;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.buildtree.BuildActionRunner;
import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.session.BuildSessionContext;
import com.tyron.builder.internal.session.BuildSessionState;
import com.tyron.builder.internal.session.state.CrossBuildSessionState;
import com.tyron.builder.launcher.exec.BuildActionExecuter;
import com.tyron.builder.launcher.exec.BuildActionParameters;
import com.tyron.builder.launcher.exec.BuildActionResult;
import com.tyron.builder.launcher.exec.BuildExecuter;
import com.tyron.builder.tooling.internal.provider.serialization.PayloadSerializer;
import com.tyron.builder.tooling.internal.provider.serialization.SerializedPayload;

/**
 * A {@link BuildExecuter} responsible for establishing the {@link BuildSessionState} to execute a {@link BuildAction} within.
 */
public class BuildSessionLifecycleBuildActionExecuter implements BuildActionExecuter<BuildActionParameters, BuildRequestContext> {
    private final ServiceRegistry globalServices;
    private final GradleUserHomeScopeServiceRegistry userHomeServiceRegistry;

    public BuildSessionLifecycleBuildActionExecuter(GradleUserHomeScopeServiceRegistry userHomeServiceRegistry, ServiceRegistry globalServices) {
        this.userHomeServiceRegistry = userHomeServiceRegistry;
        this.globalServices = globalServices;
    }

    @Override
    public BuildActionResult execute(BuildAction action, BuildActionParameters actionParameters, BuildRequestContext requestContext) {
        StartParameterInternal startParameter = action.getStartParameter();
        if (action.isCreateModel()) {
            // When creating a model, do not use continuous mode
            startParameter.setContinuous(false);
        }
        ActionImpl actionWrapper = new ActionImpl(action, requestContext);
        try {
            try (CrossBuildSessionState crossBuildSessionState = new CrossBuildSessionState(globalServices, startParameter)) {
                try (BuildSessionState buildSessionState = new BuildSessionState(userHomeServiceRegistry, crossBuildSessionState, startParameter, requestContext, actionParameters.getInjectedPluginClasspath(), requestContext.getCancellationToken(), requestContext.getClient(), requestContext.getEventConsumer())) {
                    return buildSessionState.run(actionWrapper);
                }
            }
        } catch (Throwable t) {
            if (actionWrapper.result == null) {
                // Did not create a result
                // Note: throw the failure rather than returning a result object containing the failure, as console failure logging based on the _result_ happens down in the root build scope
                // whereas console failure logging based on the _thrown exception_ happens up outside session scope. It would be better to refactor so that a result can be returned from here
                throw UncheckedException.throwAsUncheckedException(t);
            } else {
                // Created a result which may contain failures. Combine this failure with any failures that happen to be packaged in the result
                // Note: throw the failure rather than returning a result object containing the failure, as console failure logging based on the _result_ happens down in the root build scope
                // whereas console failure logging based on the _thrown exception_ happens up outside session scope. It would be better to refactor so that a result can be returned from here
                throw UncheckedException
                        .throwAsUncheckedException(actionWrapper.result.addFailure(t).getBuildFailure());
            }
        }
    }

    private static RuntimeException wrap(Throwable failure) {
        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        } else {
            return new RuntimeException(failure);
        }
    }

    private static class ActionImpl implements Function<BuildSessionContext, BuildActionResult> {
        private final BuildAction action;
        private final BuildRequestContext requestContext;
        private BuildActionRunner.Result result;

        public ActionImpl(BuildAction action, BuildRequestContext requestContext) {
            this.action = action;
            this.requestContext = requestContext;
        }

        @Override
        public BuildActionResult apply(BuildSessionContext context) {
            result = context.execute(action);
            PayloadSerializer payloadSerializer = context.getServices().get(PayloadSerializer.class);
            if (result.getBuildFailure() == null) {
                if (result.getClientResult() instanceof SerializedPayload) {
                    // Already serialized
                    return BuildActionResult.of((SerializedPayload) result.getClientResult());
                } else {
                    return BuildActionResult.of(payloadSerializer.serialize(result.getClientResult()));
                }
            }
            if (requestContext.getCancellationToken().isCancellationRequested()) {
                return BuildActionResult.cancelled(payloadSerializer.serialize(result.getBuildFailure()));
            }
            return BuildActionResult.failed(payloadSerializer.serialize(result.getClientFailure()));
//            return BuildActionResult.failed(wrap(result.getClientFailure()));
        }
    }
}
