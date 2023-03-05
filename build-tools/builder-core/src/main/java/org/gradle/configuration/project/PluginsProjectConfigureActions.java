package org.gradle.configuration.project;

import com.google.common.collect.ImmutableList;
import org.gradle.api.Action;
import org.gradle.api.internal.InternalAction;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.internal.service.ServiceLocator;

public class PluginsProjectConfigureActions implements ProjectConfigureAction, InternalAction<ProjectInternal> {

    public static ProjectConfigureAction from(ServiceLocator serviceLocator) {
        return of(ProjectConfigureAction.class, serviceLocator);
    }

    public static <T extends Action<ProjectInternal>> ProjectConfigureAction of(Class<T> serviceType,
                                                                                ServiceLocator serviceLocator) {
        return new PluginsProjectConfigureActions(ImmutableList.<Action<ProjectInternal>>copyOf(
                serviceLocator.getAll(serviceType)));
    }

    private final Iterable<Action<ProjectInternal>> actions;

    private PluginsProjectConfigureActions(Iterable<Action<ProjectInternal>> actions) {
        this.actions = actions;
    }

    @Override
    public void execute(ProjectInternal project) {
        for (Action<ProjectInternal> action : actions) {
            action.execute(project);
        }
    }
}