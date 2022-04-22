package com.tyron.builder.internal.resource.local;

import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

import java.io.File;

@EventScope(Scopes.Build.class)
public interface FileResourceListener {
    /**
     * Called when a file system resource is accessed.
     */
    void fileObserved(File file);
}
