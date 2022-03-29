package com.tyron.builder.api.internal.reflect.service.scopes;

import com.tyron.builder.api.execution.plan.ExecutionNodeAccessHierarchies;
import com.tyron.builder.api.execution.plan.LocalTaskNodeExecutor;
import com.tyron.builder.api.execution.plan.NodeExecutor;
import com.tyron.builder.api.execution.plan.PlanExecutor;
import com.tyron.builder.api.internal.DefaultGradle;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.concurrent.CompositeStoppable;
import com.tyron.builder.api.internal.execution.DefaultTaskExecutionGraph;
import com.tyron.builder.api.internal.execution.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.project.ProjectFactory;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;

import java.io.File;
import java.util.List;

public class GradleScopeServices extends DefaultServiceRegistry {

    private final CompositeStoppable registries = new CompositeStoppable();

    public GradleScopeServices(final ServiceRegistry parent) {
        super(parent);
        register(registration -> {
//            for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
//                pluginServiceRegistry.registerGradleServices(registration);
//            }
            registration.add(ProjectFactory.class);
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
            List<NodeExecutor> nodeExecutors,
            GradleInternal gradle,
            ServiceRegistry gradleScopedServices
    ) {
        return new DefaultTaskExecutionGraph(
                planExecutor,
                nodeExecutors,
                gradle,
                gradleScopedServices
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

    FileSystem createFileSystem() {
        return new FileSystem() {
            @Override
            public boolean isCaseSensitive() {
                return true;
            }

            @Override
            public boolean canCreateSymbolicLink() {
                return false;
            }

            @Override
            public void createSymbolicLink(File link, File target) throws FileException {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isSymlink(File suspect) {
                return false;
            }

            @Override
            public void chmod(File file, int mode) throws FileException {

            }

            @Override
            public int getUnixMode(File f) throws FileException {
                return 0;
            }

            @Override
            public FileMetadata stat(File f) throws FileException {
                return null;
            }
        };
    }

    @Override
    public void close() {
        registries.stop();
        super.close();
    }
}
