package org.gradle.tooling.internal.provider.runner;

import org.gradle.initialization.BuildEventConsumer;
import org.gradle.internal.buildtree.BuildActionRunner;
import org.gradle.internal.buildtree.BuildTreeLifecycleController;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.tooling.internal.protocol.InternalPhasedAction;
import org.gradle.tooling.internal.protocol.PhasedActionResult;
import org.gradle.tooling.internal.provider.PhasedBuildActionResult;
import org.gradle.tooling.internal.provider.action.ClientProvidedPhasedAction;
import org.gradle.tooling.internal.provider.serialization.PayloadSerializer;
import org.gradle.tooling.internal.provider.serialization.SerializedPayload;

import javax.annotation.Nullable;

public class ClientProvidedPhasedActionRunner extends AbstractClientProvidedBuildActionRunner implements BuildActionRunner {
    private final PayloadSerializer payloadSerializer;
    private final BuildEventConsumer buildEventConsumer;

    public ClientProvidedPhasedActionRunner(BuildControllerFactory buildControllerFactory,
                                            PayloadSerializer payloadSerializer,
                                            BuildEventConsumer buildEventConsumer) {
        super(buildControllerFactory, payloadSerializer);
        this.payloadSerializer = payloadSerializer;
        this.buildEventConsumer = buildEventConsumer;
    }

    @Override
    public Result run(BuildAction action, BuildTreeLifecycleController buildController) {
        if (!(action instanceof ClientProvidedPhasedAction)) {
            return Result.nothing();
        }

        ClientProvidedPhasedAction clientProvidedPhasedAction = (ClientProvidedPhasedAction) action;
        InternalPhasedAction phasedAction = (InternalPhasedAction) payloadSerializer.deserialize(clientProvidedPhasedAction.getPhasedAction());

        return runClientAction(new ClientActionImpl(phasedAction, action), buildController);
    }

    private class ClientActionImpl implements ClientAction {
        private final InternalPhasedAction phasedAction;
        private final BuildAction action;

        public ClientActionImpl(InternalPhasedAction phasedAction, BuildAction action) {
            this.phasedAction = phasedAction;
            this.action = action;
        }

        @Override
        public Object getProjectsEvaluatedAction() {
            return phasedAction.getProjectsLoadedAction();
        }

        @Override
        public Object getBuildFinishedAction() {
            return phasedAction.getBuildFinishedAction();
        }

        @Override
        public void collectActionResult(SerializedPayload serializedResult, PhasedActionResult.Phase phase) {
            PhasedBuildActionResult res = new PhasedBuildActionResult(serializedResult, phase);
            buildEventConsumer.dispatch(res);
        }

        @Nullable
        @Override
        public SerializedPayload getResult() {
            return null;
        }

        @Override
        public boolean isRunTasks() {
            return action.isRunTasks();
        }
    }
}