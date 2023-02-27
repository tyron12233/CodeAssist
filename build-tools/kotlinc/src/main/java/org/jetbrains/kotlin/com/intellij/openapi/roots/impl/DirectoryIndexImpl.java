package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.injected.editor.VirtualFileWindow;
import org.jetbrains.kotlin.com.intellij.model.ModelBranch;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.SourceFolder;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.LowMemoryWatcher;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.BulkFileListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.CollectionQuery;
import org.jetbrains.kotlin.com.intellij.util.Query;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.messages.MessageBusConnection;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * This is an internal class, {@link DirectoryIndex} must be used instead.
 */
@ApiStatus.Internal
public final class DirectoryIndexImpl extends DirectoryIndex implements Disposable {
  private static final Logger LOG = Logger.getInstance(DirectoryIndexImpl.class);

  private final Project myProject;
  private final MessageBusConnection myConnection;

  private volatile boolean myDisposed;
  private volatile RootIndex myRootIndex;

  public DirectoryIndexImpl(@NotNull Project project) {
    myProject = project;
    myConnection = project.getMessageBus().connect();
    subscribeToFileChanges();
    LowMemoryWatcher.register(() -> {
      RootIndex index = myRootIndex;
      if (index != null) {
        index.onLowMemory();
      }
    }, this);
  }

  @Override
  public void dispose() {
    myDisposed = true;
    myRootIndex = null;
  }

  private void subscribeToFileChanges() {
    myConnection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
      @Override
      public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
        RootIndex rootIndex = myRootIndex;
        if (rootIndex != null && shouldResetOnEvents(events)) {
          rootIndex.myPackageDirectoryCache.clear();
          for (VFileEvent event : events) {
            if (isIgnoredFileCreated(event)) {
              reset();
              break;
            }
          }
        }
      }
    });
  }

  public static boolean shouldResetOnEvents(@NotNull List<? extends VFileEvent> events) {
    for (VFileEvent event : events) {
      // VFileCreateEvent.getFile() is expensive
      if (event instanceof VFileCreateEvent) {
          if (((VFileCreateEvent) event).isDirectory()) {
              return true;
          }
      }
      else {
        VirtualFile file = event.getFile();
        if (file == null || file.isDirectory()) {
          return true;
        }
      }
    }
    return false;
  }

  public static boolean isIgnoredFileCreated(@NotNull VFileEvent event) {
    return event instanceof VFileMoveEvent && FileTypeRegistry.getInstance().isFileIgnored(((VFileMoveEvent)event).getNewParent()) ||
           event instanceof VFilePropertyChangeEvent &&
           ((VFilePropertyChangeEvent)event).getPropertyName().equals(VirtualFile.PROP_NAME) &&
           FileTypeRegistry.getInstance().isFileIgnored(((VFilePropertyChangeEvent)event).getFile());
  }

  private void dispatchPendingEvents() {
    myConnection.deliverImmediately();
  }

  @Override
  @NotNull
  public Query<VirtualFile> getDirectoriesByPackageName(@NotNull String packageName, boolean includeLibrarySources) {
    return getRootIndex(false).getDirectoriesByPackageName(packageName, includeLibrarySources);
  }

  @Override
  public Query<VirtualFile> getDirectoriesByPackageName(@NotNull String packageName,
                                                        @NotNull GlobalSearchScope scope) {

    Collection<ModelBranch> branches = scope.getModelBranchesAffectingScope();
    if (branches.isEmpty()) {
      return super.getDirectoriesByPackageName(packageName, scope);
    }

    List<RootIndex> map = ContainerUtil.map(branches, DirectoryIndexImpl::obtainBranchRootIndex);
    map.add(getRootIndex(false));
    return new CollectionQuery<>(map)
      .flatMapping(i -> i.getDirectoriesByPackageName(packageName, true))
      .filtering(scope::contains);
  }

  @NotNull
  RootIndex getRootIndex(VirtualFile file) {
    ModelBranch branch = ModelBranch.getFileBranch(file);
    if (branch != null) {
      return obtainBranchRootIndex(branch);
    }
    return getRootIndex(false);
  }

  private static final Key<Pair<Long, RootIndex>> BRANCH_ROOT_INDEX = Key.create("BRANCH_ROOT_INDEX");

  private static RootIndex obtainBranchRootIndex(ModelBranch branch) {
    Pair<Long, RootIndex> pair = branch.getUserData(BRANCH_ROOT_INDEX);
    long modCount = 0;//branch.getBranchedVfsStructureModificationCount();
    if (pair == null || pair.first != modCount) {
      pair = Pair.create(modCount, new RootIndex(branch.getProject(), RootFileSupplier.forBranch(branch)));
      branch.putUserData(BRANCH_ROOT_INDEX, pair);
    }
    return pair.second;
  }

  RootIndex getRootIndex(boolean forOrderEntryGraph) {
    RootIndex rootIndex = myRootIndex;
    if (rootIndex == null) {
      myRootIndex = rootIndex = new RootIndex(myProject);
    }
    return rootIndex;
  }

  @NotNull
  @Override
  public DirectoryInfo getInfoForFile(@NotNull VirtualFile file) {
    checkAvailability();
    ProgressManager.checkCanceled();
//    SlowOperations.assertSlowOperationsAreAllowed();
    dispatchPendingEvents();
    return getRootIndex(file).getInfoForFile(file);
  }

  @Nullable
  @Override
  public SourceFolder getSourceRootFolder(@NotNull DirectoryInfo info) {
    boolean inModuleSource = info instanceof DirectoryInfoImpl && ((DirectoryInfoImpl)info).isInModuleSource();
    if (inModuleSource) {
      return info.getSourceRootFolder();
    }
    return null;
  }
//
//  @Override
//  @Nullable
//  public JpsModuleSourceRootType<?> getSourceRootType(@NotNull DirectoryInfo info) {
//    SourceFolder folder = getSourceRootFolder(info);
//    return folder == null ? null : folder.getRootType();
//  }

  @Override
  public String getPackageName(@NotNull VirtualFile dir) {
    checkAvailability();

    return getRootIndex(dir).getPackageName(dir);
  }

  @NotNull
  @Override
  public List<OrderEntry> getOrderEntries(@NotNull VirtualFile fileOrDir) {
    checkAvailability();
      if (myProject.isDefault()) {
          return Collections.emptyList();
      }
    
    if (fileOrDir instanceof VirtualFileWindow) {
      fileOrDir = ((VirtualFileWindow)fileOrDir).getDelegate();
    }
//    fileOrDir = BackedVirtualFile.getOriginFileIfBacked(fileOrDir);
    DirectoryInfo info = getInfoForFile(fileOrDir);
      if (!(info instanceof DirectoryInfoImpl)) {
          return Collections.emptyList();
      }
    return getRootIndex(true).getOrderEntries(((DirectoryInfoImpl)info).getRoot());
  }

//  @Override
  @NotNull
  public Set<String> getDependentUnloadedModules(@NotNull Module module) {
    checkAvailability();
    return getRootIndex(true).getDependentUnloadedModules(module);
  }

  private void checkAvailability() {
    ApplicationManager.getApplication().assertReadAccessAllowed();
    if (myDisposed) {
      ProgressManager.checkCanceled();
      LOG.error("Directory index is already disposed for " + myProject);
    }
  }

  void reset() {
    myRootIndex = null;
  }
}