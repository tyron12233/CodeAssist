package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.initialization.DefaultProjectDescriptor;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.model.StateTransitionController;
import com.tyron.builder.internal.model.StateTransitionControllerFactory;

@ServiceScope(Scopes.Project.class)
public class ProjectLifecycleController {
    private ProjectInternal project;

    private enum State implements StateTransitionController.State {
        NotCreated, Created, Configured
    }

    private final StateTransitionController<State> controller;

    public ProjectLifecycleController(DisplayName displayName, StateTransitionControllerFactory factory) {
        controller = factory.newController(displayName, State.NotCreated);
    }

    public void createMutableModel(
            DefaultProjectDescriptor descriptor,
            BuildState build,
            ProjectStateUnk owner,
            ClassLoaderScope selfClassLoaderScope,
            ClassLoaderScope baseClassLoaderScope,
            IProjectFactory projectFactory
    ) {
        controller.transition(State.NotCreated, State.Created, () -> {
            ProjectStateUnk parent = owner.getBuildParent();
            ProjectInternal parentModel = parent == null ? null : parent.getMutableModel();
            project = projectFactory.createProject(build.getMutableModel(), descriptor, owner, parentModel, selfClassLoaderScope, baseClassLoaderScope);
        });
    }

    public ProjectInternal getMutableModel() {
        controller.assertInStateOrLater(State.Created);
        return project;
    }

    public void ensureSelfConfigured() {
        controller.maybeTransitionIfNotCurrentlyTransitioning(State.Created, State.Configured, () -> project.evaluate());
    }

    public void ensureTasksDiscovered() {
        ensureSelfConfigured();
        project.getTasks().discoverTasks();
        project.bindAllModelRules();
    }
}
