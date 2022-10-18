package org.gradle.internal.resource.local;

import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scopes;

import java.io.File;

@EventScope(Scopes.Build.class)
public interface FileResourceListener {
    /**
     * Called when a file system resource is accessed.
     */
    void fileObserved(File file);
}
