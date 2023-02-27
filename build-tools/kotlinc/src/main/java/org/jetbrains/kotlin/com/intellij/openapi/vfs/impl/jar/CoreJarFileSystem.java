package org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar;

import java.util.Map;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.Couple;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.DeprecatedVirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentFactoryMap;

public class CoreJarFileSystem extends DeprecatedVirtualFileSystem {
    private final Map<String, CoreJarHandler> myHandlers = ConcurrentFactoryMap.createMap((key) -> {
        return new CoreJarHandler(this, key);
    });

    public CoreJarFileSystem() {
    }

    public @NotNull String getProtocol() {
        return "jar";
    }

    public VirtualFile findFileByPath(@NotNull @NonNls String path) {
        Couple<String> pair = splitPath(path);
        return ((CoreJarHandler)this.myHandlers.get(pair.first)).findFileByPath((String)pair.second);
    }

    static @NotNull Couple<String> splitPath(@NotNull String path) {

        int separator = path.indexOf("!/");
        if (separator < 0) {
            throw new IllegalArgumentException("Path in JarFileSystem must contain a separator: " + path);
        } else {
            String localPath = path.substring(0, separator);
            String pathInJar = path.substring(separator + 2);
            return Couple.of(localPath, pathInJar);
        }
    }

    public void refresh(boolean asynchronous) {
    }

    public VirtualFile refreshAndFindFileByPath(@NotNull String path) {
        return this.findFileByPath(path);
    }

    public void clearHandlersCache() {
        this.myHandlers.clear();
    }
}
