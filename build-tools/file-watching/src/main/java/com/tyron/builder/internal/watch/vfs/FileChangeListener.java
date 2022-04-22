package com.tyron.builder.internal.watch.vfs;

import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scope;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.watch.registry.FileWatcherRegistry;

// TODO: Change this to gradle user home
@EventScope(Scope.Global.class)
public interface FileChangeListener extends FileWatcherRegistry.ChangeHandler {
}
