package org.gradle.internal.buildtree;

import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.ServiceRegistryBuilder;

import java.io.Closeable;
import java.util.function.Function;

public class BuildTreeState implements Closeable {
    private final ServiceRegistry services;
    private final DefaultBuildTreeContext context;

    public BuildTreeState(ServiceRegistry parent, BuildTreeModelControllerServices.Supplier modelServices) {
        services = ServiceRegistryBuilder.builder()
                .displayName("build tree services")
                .parent(parent)
                .provider(new BuildTreeScopeServices(this, modelServices))
                .build();
        context = new DefaultBuildTreeContext(services);
    }

    public ServiceRegistry getServices() {
        return services;
    }

    /**
     * Runs the given action against the state of this build tree.
     */
    public <T> T run(Function<? super BuildTreeContext, T> action) {
        return action.apply(context);
    }

    @Override
    public void close() {
        CompositeStoppable.stoppable(services).stop();
    }
}