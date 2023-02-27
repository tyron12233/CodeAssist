//package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent;
//
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.annotations.Nullable;
//import org.jetbrains.kotlin.com.intellij.concurrency.ConcurrentCollectionFactory;
//import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentIntObjectHashMap;
//
//import java.util.Collection;
//
//final class VirtualDirectoryCache {
//  // FS roots must be in this map too. findFileById() relies on this.
//  private final ConcurrentIntObjectMap<VirtualFileSystemEntry> myIdToDirCache =
//    new ConcurrentIntObjectHashMap<>();
//
//  @NotNull VirtualFileSystemEntry getOrCacheDir(@NotNull VirtualFileSystemEntry newDir) {
//    int id = newDir.getId();
//    VirtualFileSystemEntry dir = myIdToDirCache.get(id);
//      if (dir != null) {
//          return dir;
//      }
//    return myIdToDirCache.cacheOrGet(id, newDir);
//  }
//
//  void cacheDir(@NotNull VirtualFileSystemEntry newDir) {
//    myIdToDirCache.put(newDir.getId(), newDir);
//  }
//
//  @Nullable VirtualFileSystemEntry cacheDirIfAbsent(@NotNull VirtualFileSystemEntry newDir) {
//    return myIdToDirCache.putIfAbsent(newDir.getId(), newDir);
//  }
//
//  @Nullable VirtualFileSystemEntry getCachedDir(int id) {
//    return myIdToDirCache.get(id);
//  }
//
//  void dropNonRootCachedDirs() {
//    myIdToDirCache.entrySet().removeIf(e -> e.getValue().getParent() != null);
//  }
//
//  void remove(int id) {
//    myIdToDirCache.remove(id);
//  }
//
//  @NotNull Collection<VirtualFileSystemEntry> getCachedDirs() {
//    return myIdToDirCache.values();
//  }
//
//  void clear() {
//    myIdToDirCache.clear();
//  }
//}