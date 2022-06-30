package com.tyron.builder.initialization;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.internal.initialization.loadercache.ClassLoaderId;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

import javax.annotation.Nullable;


/**
 * Listens to changes to the ClassLoaderScope tree.
 *
 * @see ClassLoaderScopeRegistry
 * @see ClassLoaderScope
 */
@EventScope(Scopes.UserHome.class)
public interface ClassLoaderScopeRegistryListener {

    void childScopeCreated(ClassLoaderScopeId parentId, ClassLoaderScopeId childId);

    void classloaderCreated(ClassLoaderScopeId scopeId, ClassLoaderId classLoaderId, ClassLoader classLoader, ClassPath classPath, @Nullable HashCode implementationHash);

}
