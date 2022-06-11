package org.gradle.api.internal.project;

import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.internal.DisplayName;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.initialization.DefaultProjectDescriptor;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.model.StateTransitionController;
import org.gradle.internal.model.StateTransitionControllerFactory;

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
            ProjectState owner,
            ClassLoaderScope selfClassLoaderScope,
            ClassLoaderScope baseClassLoaderScope,
            IProjectFactory projectFactory
    ) {
        controller.transition(State.NotCreated, State.Created, () -> {
            ProjectState parent = owner.getBuildParent();
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
