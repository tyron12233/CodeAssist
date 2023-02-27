package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.diagnostic.PluginException;
import org.jetbrains.kotlin.com.intellij.model.ModelBranch;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.module.UnloadedModuleDescription;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.LibraryOrSdkOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType;
import org.jetbrains.kotlin.com.intellij.openapi.roots.SourceFolder;
import org.jetbrains.kotlin.com.intellij.openapi.roots.libraries.Library;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.VirtualFileManagerImpl;
import org.jetbrains.kotlin.com.intellij.sdk.Sdk;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.List;

@ApiStatus.Internal
public class RootFileSupplier {
  private static final Logger LOG = Logger.getInstance(RootFileSupplier.class);
  public static final RootFileSupplier INSTANCE = new RootFileSupplier();

  @NotNull
  List<@NotNull VirtualFile> getUnloadedContentRoots(UnloadedModuleDescription description) {
    return ContainerUtil.mapNotNull(description.getContentRoots(), VirtualFilePointer::getFile);
  }

  @Nullable
  public VirtualFile correctRoot(@NotNull VirtualFile file, @NotNull Object container, @Nullable Object containerProvider) {
    if (!ensureValid(file, container, containerProvider)) {
      return null;
    }
    return file;
  }

  @Nullable
  public VirtualFile findFileByUrl(String url) {
    return VirtualFileManager.getInstance().findFileByUrl(url);
  }

//  public VirtualFile @NotNull [] getExcludedRoots(LibraryEx library) {
//    return library.getExcludedRoots();
//  }

  VirtualFile @NotNull [] getLibraryRoots(LibraryOrSdkOrderEntry entry, OrderRootType type) {
    return entry.getRootFiles(type);
  }

  public VirtualFile @NotNull [] getLibraryRoots(Library library, OrderRootType type) {
    return library.getFiles(type);
  }

  VirtualFile @NotNull [] getSdkRoots(@NotNull Sdk entry, OrderRootType type) {
    return entry.getRootProvider().getFiles(type);
  }

  @Nullable
  VirtualFile getContentRoot(ContentEntry contentEntry) {
    return contentEntry.getFile();
  }

  @Nullable
  VirtualFile getSourceRoot(SourceFolder sourceFolder) {
    return sourceFolder.getFile();
  }

//  @Nullable
//  public VirtualFile findFile(@NotNull VirtualFileUrl virtualFileUrl) {
//    return VirtualFileUrls.getVirtualFile(virtualFileUrl);
//  }

  public static RootFileSupplier forBranch(ModelBranch branch) {
    return new RootFileSupplier() {
//      @Override
//      public VirtualFile @NotNull [] getExcludedRoots(LibraryEx library) {
//        return ContainerUtil.mapNotNull(library.getExcludedRootUrls(), this::findFileByUrl).toArray(VirtualFile.EMPTY_ARRAY);
//      }

      @Override
      protected VirtualFile @NotNull [] getLibraryRoots(LibraryOrSdkOrderEntry entry, OrderRootType type) {
        return ContainerUtil.mapNotNull(entry.getRootUrls(type), this::findFileByUrl).toArray(VirtualFile.EMPTY_ARRAY);
      }

      @Override
      public VirtualFile @NotNull [] getLibraryRoots(Library library, OrderRootType type) {
        return ContainerUtil.mapNotNull(library.getUrls(type), this::findFileByUrl).toArray(VirtualFile.EMPTY_ARRAY);
      }

      @Override
      VirtualFile @NotNull [] getSdkRoots(@NotNull Sdk sdk, OrderRootType type) {
        return ContainerUtil.mapNotNull(sdk.getRootProvider().getUrls(type), this::findFileByUrl).toArray(VirtualFile.EMPTY_ARRAY);
      }

      @Override
      protected @Nullable VirtualFile getContentRoot(ContentEntry contentEntry) {
        return findFileByUrl(contentEntry.getUrl());
      }

      @Override
      protected @Nullable VirtualFile getSourceRoot(SourceFolder sourceFolder) {
        return findFileByUrl(sourceFolder.getUrl());
      }

//      @Override
//      public @Nullable VirtualFile findFile(@NotNull VirtualFileUrl virtualFileUrl) {
//        return findFileByUrl(virtualFileUrl.getUrl());
//      }

      @Override
      protected @NotNull List<@NotNull VirtualFile> getUnloadedContentRoots(UnloadedModuleDescription description) {
        return ContainerUtil.mapNotNull(description.getContentRoots(), p -> findFileByUrl(p.getUrl()));
      }

      @Override
      @Nullable
      public VirtualFile correctRoot(@NotNull VirtualFile file, @NotNull Object container, @Nullable Object containerProvider) {
        file = super.correctRoot(file, container, containerProvider);
//        if (file != null) {
//          file = branch.findFileCopy(file);
//          if (!file.isValid()) {
//            return null;
//          }
//        }
        return file;
      }

      @Override
      public @Nullable VirtualFile findFileByUrl(String url) {
        return VirtualFileManagerImpl.getInstance().findFileByUrl(url);
      }

    };
  }

  public static boolean ensureValid(@NotNull VirtualFile file, @NotNull Object container, @Nullable Object containerProvider) {
    if (!file.isValid()) {
      if (containerProvider != null) {
        Class<?> providerClass = containerProvider.getClass();
        PluginException.logPluginError(LOG, "Invalid root " + file + " in " + container + " provided by " + providerClass, null, providerClass);
      }
      else {
        LOG.error("Invalid root " + file + " in " + container);
      }
      return false;
    }
    return true;
  }


}