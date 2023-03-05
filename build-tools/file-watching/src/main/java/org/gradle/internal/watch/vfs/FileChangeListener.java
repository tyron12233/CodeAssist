package org.gradle.internal.watch.vfs;

import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scope;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.watch.registry.FileWatcherRegistry;

// TODO: Change this to gradle user home
@EventScope(Scope.Global.class)
public interface FileChangeListener extends FileWatcherRegistry.ChangeHandler {
}
