//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.ArchiveHandler;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.ZipHandler;
import org.jetbrains.kotlin.com.intellij.util.containers.FactoryMap;

class CoreJarHandler extends ZipHandler {
    private final CoreJarFileSystem myFileSystem;
    private final VirtualFile myRoot;

    CoreJarHandler(@NotNull CoreJarFileSystem fileSystem, @NotNull String path) {
        super(path);
        this.myFileSystem = fileSystem;
        Map<ArchiveHandler.EntryInfo, CoreJarVirtualFile> entries = new HashMap<>();
        Map<String, ArchiveHandler.EntryInfo> entriesMap = this.getEntriesMap();
        Map<CoreJarVirtualFile, List<VirtualFile>> childrenMap = FactoryMap.create((key) -> new ArrayList<>());

        for (EntryInfo info : entriesMap.values()) {
            CoreJarVirtualFile file = this.getOrCreateFile(info, entries);
            VirtualFile parent = file.getParent();
            if (parent != null) {
                childrenMap.get(parent).add(file);
            }
        }

        ArchiveHandler.EntryInfo rootInfo = this.getEntryInfo("");
        this.myRoot = rootInfo != null ? this.getOrCreateFile(rootInfo, entries) : null;

        for (Map.Entry<CoreJarVirtualFile, List<VirtualFile>> entry : childrenMap.entrySet()) {
            List<VirtualFile> childList = (List<VirtualFile>) entry.getValue();
            ((CoreJarVirtualFile) entry.getKey()).setChildren((VirtualFile[]) childList.toArray(
                    VirtualFile.EMPTY_ARRAY));
        }

    }

    private @NotNull CoreJarVirtualFile getOrCreateFile(@NotNull ArchiveHandler.@NotNull EntryInfo info, @NotNull Map<ArchiveHandler.EntryInfo, CoreJarVirtualFile> entries) {
        CoreJarVirtualFile file = (CoreJarVirtualFile)entries.get(info);
        if (file == null) {
            ArchiveHandler.EntryInfo parent = info.parent;
            file = new CoreJarVirtualFile(this, info.shortName, info.isDirectory ? -1L : info.length, info.timestamp, parent != null ? this.getOrCreateFile(parent, entries) : null);
            entries.put(info, file);
        }

        return file;
    }

    @Nullable VirtualFile findFileByPath(@NotNull String pathInJar) {
        return this.myRoot != null ? this.myRoot.findFileByRelativePath(pathInJar) : null;
    }

    @NotNull CoreJarFileSystem getFileSystem() {
        return this.myFileSystem;
    }
}
