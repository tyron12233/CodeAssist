package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.internal.file.temp.DefaultTemporaryFileProvider;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependencyFactory;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;

import java.io.File;

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
        });
        addProvider(new WorkerSharedProjectScopeServices(project.getProjectDir()));
    }

    protected TaskContainerInternal createTaskContainerInternal(
            BuildOperationExecutor buildOperationExecutor
    ) {
        return new DefaultTaskContainer(project, buildOperationExecutor);
    }

    protected TaskDependencyFactory createTaskDependencyFactory() {
        return DefaultTaskDependencyFactory.forProject(project.getTasks());
    }

    protected TemporaryFileProvider createTemporaryFileProvider() {
        return new DefaultTemporaryFileProvider(() -> new File(project.getBuildDir(), "tmp"));
    }

}
