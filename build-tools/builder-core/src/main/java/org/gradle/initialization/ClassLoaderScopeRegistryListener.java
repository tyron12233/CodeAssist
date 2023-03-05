package org.gradle.initialization;

import com.google.common.hash.HashCode;
import org.gradle.api.internal.initialization.loadercache.ClassLoaderId;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scopes;

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
