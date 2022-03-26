package com.tyron.builder.api.internal.service.scopes;

import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.service.ServiceRegistry;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;

public class ProjectScopeServices extends DefaultServiceRegistry {

    private final ProjectInternal project;

    public ProjectScopeServices(final ServiceRegistry parent, final ProjectInternal project) {
        super(parent);
        this.project = project;
//        this.loggingManagerInternalFactory = loggingManagerInternalFactory;
        register(registration -> {
            registration.add(ProjectInternal.class, project);
//            parent.get(DependencyManagementServices.class).addDslServices(registration, project);
//            for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
//                pluginServiceRegistry.registerProjectServices(registration);
//            }

            registration.add(TaskContainerInternal.class, new DefaultTaskContainer(project));
        });
        addProvider(new WorkerSharedProjectScopeServices(project.getProjectDir()));
    }

    protected TaskContainerInternal createTaskContainerInternal() {
        return new DefaultTaskContainer(project);
    }

}
