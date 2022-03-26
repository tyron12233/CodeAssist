package com.tyron.builder.api.internal.service.scopes;

import com.tyron.builder.api.execution.plan.ExecutionNodeAccessHierarchies;
import com.tyron.builder.api.execution.plan.LocalTaskNodeExecutor;
import com.tyron.builder.api.execution.plan.NodeExecutor;
import com.tyron.builder.api.execution.plan.PlanExecutor;
import com.tyron.builder.api.internal.concurrent.CompositeStoppable;
import com.tyron.builder.api.internal.execution.DefaultTaskExecutionGraph;
import com.tyron.builder.api.internal.execution.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.service.ServiceRegistry;

import java.util.List;

public class GradleScopeServices extends DefaultServiceRegistry {

    private final CompositeStoppable registries = new CompositeStoppable();

    public GradleScopeServices(final ServiceRegistry parent) {
        super(parent);
        register(registration -> {
//            for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
//                pluginServiceRegistry.registerGradleServices(registration);
//            }
        });
    }

    LocalTaskNodeExecutor createLocalTaskNodeExecutor(ExecutionNodeAccessHierarchies executionNodeAccessHierarchies) {
        return new LocalTaskNodeExecutor(
                executionNodeAccessHierarchies.getOutputHierarchy()
        );
    }

//    WorkNodeExecutor createWorkNodeExecutor() {
//        return new WorkNodeExecutor();
//    }

    TaskExecutionGraphInternal createTaskExecutionGraph(
            PlanExecutor planExecutor,
            List<NodeExecutor> nodeExecutors
    ) {
        return new DefaultTaskExecutionGraph(
                planExecutor,
                nodeExecutors
        );
    }

    ServiceRegistryFactory createServiceRegistryFactory(final ServiceRegistry services) {
//        final Factory<LoggingManagerInternal> loggingManagerInternalFactory = getFactory(LoggingManagerInternal.class);
        return new ServiceRegistryFactory() {
            @Override
            public ServiceRegistry createFor(Object domainObject) {
                if (domainObject instanceof ProjectInternal) {
                    ProjectScopeServices projectScopeServices = new ProjectScopeServices(services, (ProjectInternal) domainObject);
                    registries.add(projectScopeServices);
                    return projectScopeServices;
                }
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public void close() {
        registries.stop();
        super.close();
    }
}
