package com.tyron.builder.internal.model;

import com.tyron.builder.api.internal.DisplayName;
import com.tyron.builder.api.internal.service.scopes.Scopes;
import com.tyron.builder.api.internal.service.scopes.ServiceScope;
import com.tyron.builder.api.internal.work.WorkerLeaseService;

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