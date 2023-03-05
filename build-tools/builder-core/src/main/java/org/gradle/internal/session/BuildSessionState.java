package org.gradle.internal.session;

import org.gradle.initialization.BuildCancellationToken;
import org.gradle.api.internal.StartParameterInternal;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.ServiceRegistryBuilder;
import org.gradle.initialization.BuildClientMetaData;
import org.gradle.initialization.BuildEventConsumer;
import org.gradle.initialization.BuildRequestMetaData;
import org.gradle.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import org.gradle.internal.session.state.CrossBuildSessionState;

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