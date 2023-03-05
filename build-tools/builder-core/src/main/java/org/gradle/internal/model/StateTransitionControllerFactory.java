package org.gradle.internal.model;

import org.gradle.internal.DisplayName;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.internal.work.WorkerLeaseService;

@ServiceScope(Scopes.BuildSession.class)
public class StateTransitionControllerFactory {
    private final WorkerLeaseService workerLeaseService;

    public StateTransitionControllerFactory(WorkerLeaseService workerLeaseService) {
        this.workerLeaseService = workerLeaseService;
    }

    public <T extends StateTransitionController.State> StateTransitionController<T> newController(DisplayName displayName, T initialState) {
        return new StateTransitionController<>(displayName, initialState, workerLeaseService.newResource());
    }
}