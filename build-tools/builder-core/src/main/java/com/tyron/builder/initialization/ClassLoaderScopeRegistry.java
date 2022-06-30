package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.UserHome.class)
public interface ClassLoaderScopeRegistry {

    ClassLoaderScope getCoreAndPluginsScope();

    ClassLoaderScope getCoreScope();

}
