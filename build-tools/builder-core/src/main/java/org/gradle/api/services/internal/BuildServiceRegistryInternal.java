package org.gradle.api.services.internal;

import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.api.services.BuildServiceRegistry;
import org.gradle.internal.resources.SharedResource;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

import javax.annotation.Nullable;

@ServiceScope(Scopes.Gradle.class)
public interface BuildServiceRegistryInternal extends BuildServiceRegistry {
    /**
     * @param maxUsages Same semantics as {@link SharedResource#getMaxUsages()}.
     */
    BuildServiceProvider<?, ?> register(String name, Class<? extends BuildService<?>> implementationType, @Nullable BuildServiceParameters parameters, int maxUsages);

    SharedResource forService(Provider<? extends BuildService<?>> service);
}