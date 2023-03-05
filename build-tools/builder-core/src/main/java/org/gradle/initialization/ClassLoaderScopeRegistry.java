package org.gradle.initialization;

import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.UserHome.class)
public interface ClassLoaderScopeRegistry {

    ClassLoaderScope getCoreAndPluginsScope();

    ClassLoaderScope getCoreScope();

}
