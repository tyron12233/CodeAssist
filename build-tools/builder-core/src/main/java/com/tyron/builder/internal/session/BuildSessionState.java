package com.tyron.builder.internal.session;

import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.ServiceRegistryBuilder;
import com.tyron.builder.initialization.BuildClientMetaData;
import com.tyron.builder.initialization.BuildEventConsumer;
import com.tyron.builder.initialization.BuildRequestMetaData;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.session.state.CrossBuildSessionState;

import java.io.Closeable;
import java.util.function.Function;

/**
 * Encapsulates the state for a build session.
 */
public class BuildSessionState implements Closeable {
    private final GradleUserHomeScopeServiceRegistry userHomeScopeServiceRegistry;
    private final ServiceRegistry userHomeServices;
    private final ServiceRegistry sessionScopeServices;
    private final DefaultBuildSessionContext context;

    public BuildSessionState(GradleUserHomeScopeServiceRegistry userHomeScopeServiceRegistry,
                             CrossBuildSessionState crossBuildSessionServices,
                             StartParameterInternal startParameter,
                             BuildRequestMetaData requestMetaData,
                             ClassPath injectedPluginClassPath,
                             BuildCancellationToken buildCancellationToken,
                             BuildClientMetaData buildClientMetaData,
                             BuildEventConsumer buildEventConsumer) {
        this.userHomeScopeServiceRegistry = userHomeScopeServiceRegistry;
        userHomeServices = userHomeScopeServiceRegistry.getServicesFor(startParameter.getGradleUserHomeDir());
        sessionScopeServices = ServiceRegistryBuilder.builder()
                .displayName("build session services")
                .parent(userHomeServices)
                .parent(crossBuildSessionServices.getServices())
                .provider(new BuildSessionScopeServices(startParameter, requestMetaData, injectedPluginClassPath, buildCancellationToken, buildClientMetaData, buildEventConsumer))
                .build();
        context = new DefaultBuildSessionContext(sessionScopeServices);
    }

    public ServiceRegistry getServices() {
        return sessionScopeServices;
    }

    /**
     * Runs the given action against the build session state. Should be called once only for a given session instance.
     */
    public <T> T run(Function<? super BuildSessionContext, T> action) {
        return action.apply(context);
    }

    @Override
    public void close() {
        CompositeStoppable.stoppable(sessionScopeServices, (Closeable) () -> userHomeScopeServiceRegistry.release(userHomeServices)).stop();
    }
}