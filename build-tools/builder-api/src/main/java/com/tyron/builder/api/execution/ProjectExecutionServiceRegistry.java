package com.tyron.builder.api.execution;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.tyron.builder.api.internal.concurrent.CompositeStoppable;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.ServiceLookupException;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.tasks.NodeExecutionContext;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;


public class ProjectExecutionServiceRegistry implements Closeable {

    private final NodeExecutionContext global;
    private final LoadingCache<ProjectInternal, NodeExecutionContext> projectRegistries = CacheBuilder
            .newBuilder()
            .build(new CacheLoader<ProjectInternal, NodeExecutionContext>() {
                @Override
                public NodeExecutionContext load(@NotNull ProjectInternal project) {
                    return new DefaultNodeExecutionContext(new ProjectExecutionServices(project));
                }
            });

    public ProjectExecutionServiceRegistry(ServiceRegistry globalServices) {
        global = globalServices::get;
    }

    public NodeExecutionContext forProject(@Nullable ProjectInternal project) {
        if (project == null) {
            return global;
        }
        return projectRegistries.getUnchecked(project);
    }

    @Override
    public void close() {
        CompositeStoppable.stoppable(projectRegistries.asMap().values()).stop();
    }

    private static class DefaultNodeExecutionContext implements NodeExecutionContext, Closeable {
        private final ProjectExecutionServices services;

        public DefaultNodeExecutionContext(ProjectExecutionServices services) {
            this.services = services;
        }

        @Override
        public <T> T getService(Class<T> type) throws ServiceLookupException {
            return services.get(type);
        }

        @Override
        public void close() throws IOException {
            services.close();
        }
    }
}
