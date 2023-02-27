package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.project.ProjectCoreUtil;
import org.jetbrains.kotlin.com.intellij.openapi.util.ClearableLazyValue;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.tree.StubFileElementType;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.*;


import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import kotlin.Unit;

public final class PerFileElementTypeStubModificationTracker implements StubIndexImpl.FileUpdateProcessor {
  static final Logger LOG = Logger.getInstance(PerFileElementTypeStubModificationTracker.class);
  public static final int PRECISE_CHECK_THRESHOLD =
    SystemProperties.getIntProperty("stub.index.per.file.element.type.modification.tracker.precise.check.threshold", 20);

  private final ConcurrentMap<String, List<StubFileElementType<?>>> myFileElementTypesCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<StubFileElementType<?>, Long> myModCounts = new ConcurrentHashMap<>();
  private final ClearableLazyValue<StubUpdatingIndexStorage> myStubUpdatingIndexStorage = ClearableLazyValue.createAtomic(() -> {
    final FileBasedIndexImpl fileBasedIndex = (FileBasedIndexImpl)FileBasedIndex.getInstance();
    fileBasedIndex.waitUntilIndicesAreInitialized();
    UpdatableIndex<?, ?, ?, ?> index = fileBasedIndex.getIndex(StubUpdatingIndex.INDEX_ID);
//    while (index instanceof FileBasedIndexInfrastructureExtensionUpdatableIndex) {
//      index = ((FileBasedIndexInfrastructureExtensionUpdatableIndex<?, ?, ?, ?>)index).getBaseIndex();
//    }
    return (StubUpdatingIndexStorage)index;
  });
  private static class FileInfo {

    private final VirtualFile file;
    private final Project project;
    private final StubFileElementType<?> type;

    public FileInfo(VirtualFile file, Project project, StubFileElementType<?> type) {

      this.file = file;
      this.project = project;
      this.type = type;
    }

    public VirtualFile getFile() {
      return file;
    }

    public Project getProject() {
      return project;
    }

    public StubFileElementType<?> getType() {
      return type;
    }
  }

  private final Queue<VirtualFile> myPendingUpdates = new ArrayDeque<>();
  private final Queue<FileInfo> myProbablyExpensiveUpdates = new ArrayDeque<>();
  private final Set<StubFileElementType<?>> myModificationsInCurrentBatch = new HashSet<>();

  private void registerModificationFor(@NonNull StubFileElementType<?> fileElementType) {
    myModificationsInCurrentBatch.add(fileElementType);
    myModCounts.compute(fileElementType, (__, value) -> {
        if (value == null) {
            return 1L;
        }
      return value + 1;
    });
  }

  private boolean wereModificationsInCurrentBatch(@NonNull StubFileElementType<?> fileElementType) {
    return myModificationsInCurrentBatch.contains(fileElementType);
  }

  public Long getModificationStamp(@NonNull StubFileElementType<?> fileElementType) {
    return myModCounts.getOrDefault(fileElementType, 0L);
  }

  @Override
  public synchronized void processUpdate(@NonNull VirtualFile file) {
    myPendingUpdates.add(file);
  }

  @Override
  public synchronized void endUpdatesBatch() {
    myModificationsInCurrentBatch.clear();
    ReadAction.compute(() -> {
      fastCheck();
      if (myProbablyExpensiveUpdates.size() > PRECISE_CHECK_THRESHOLD) {
        coarseCheck();
      } else {
        preciseCheck();
      }
      return Unit.INSTANCE;
    });
  }

  private void fastCheck() {
    while (!myPendingUpdates.isEmpty()) {
      VirtualFile file = myPendingUpdates.poll();
        if (file.isDirectory()) {
            continue;
        }
      if (!file.isValid()) {
        // file is deleted or changed externally
        List<StubFileElementType<?>> beforeSuitableTypes = determinePreviousFileElementType(FileBasedIndex.getFileId(file), myStubUpdatingIndexStorage.getValue());
        for (StubFileElementType<?> type : beforeSuitableTypes) {
          registerModificationFor(type);
        }
        continue;
      }
      Project project =
              ProjectCoreUtil.theOnlyOpenProject(); //((FileBasedIndexImpl)FileBasedIndex.getInstance()).findProjectForFileId(((VirtualFileWithId)file).getId());
        if (project == null || project.isDisposed()) {
            continue;
        }
      IndexedFile indexedFile = new IndexedFileImpl(file, file.getFileType(), project);
      StubFileElementType<?> current = determineCurrentFileElementType(indexedFile);
      List<StubFileElementType<?>> beforeSuitableTypes = determinePreviousFileElementType(FileBasedIndex.getFileId(file), myStubUpdatingIndexStorage.getValue());
      if (beforeSuitableTypes.size() > 1) {
        for (StubFileElementType<?> type : beforeSuitableTypes) {
          registerModificationFor(type);
        }
          if (current != null) {
              registerModificationFor(current);
          }
        continue;
      }
      StubFileElementType<?> before = beforeSuitableTypes.isEmpty() ? null : beforeSuitableTypes.get(0);
      if (current != before) {
          if (current != null) {
              registerModificationFor(current);
          }
          if (before != null) {
              registerModificationFor(before);
          }
      }
      else {
          if (current != null) {
              myProbablyExpensiveUpdates.add(new FileInfo(file, project, current));
          }
      }
    }
  }

  private void coarseCheck() {
    while (!myProbablyExpensiveUpdates.isEmpty()) {
      FileInfo info = myProbablyExpensiveUpdates.poll();
        if (wereModificationsInCurrentBatch(info.type)) {
            continue;
        }
      registerModificationFor(info.type);
    }
  }

  private void preciseCheck() {
//    DataIndexer<Integer, SerializedStubTree, FileContent> stubIndexer = myStubUpdatingIndexStorage.getValue().getIndexer();
//    while (!myProbablyExpensiveUpdates.isEmpty()) {
//      FileInfo info = myProbablyExpensiveUpdates.poll();
//        if (wereModificationsInCurrentBatch(info.type) ||
//            (info.project != null && info.project.isDisposed())) {
//            continue;
//        }
//      FileBasedIndexImpl.markFileIndexed(info.file, null);
//      try {
//        StubCumulativeInputDiffBuilder
//                diffBuilder = (StubCumulativeInputDiffBuilder)myStubUpdatingIndexStorage.getValue().getForwardIndexAccessor()
//          .getDiffBuilder(
//            FileBasedIndex.getFileId(info.file),
//            null // see SingleEntryIndexForwardIndexAccessor#getDiffBuilder
//          );
//        // file might be deleted from actual fs, but still be "valid" in vfs (e.g. that happen sometimes in tests)
//        final FileContent fileContent = getTransientAwareFileContent(info);
//        if (fileContent == null) {
//          registerModificationFor(info.type);
//          continue;
//        }
//        final Stub stub = StubTreeBuilder.buildStubTree(fileContent);
//        Map<Integer, SerializedStubTree> serializedStub = stub == null ? Collections.emptyMap() : stubIndexer.map(fileContent);
//        if (diffBuilder.differentiate(serializedStub, (__, ___, ____) -> { }, (__, ___, ____) -> { }, (__, ___) -> { }, true)) {
//          registerModificationFor(info.type);
//        }
//      }
//      catch (IOException | StorageException e) {
//        LOG.error(e);
//      }
//      finally {
//        FileBasedIndexImpl.unmarkBeingIndexed();
//      }
//    }
  }

  private static @Nullable FileContent getTransientAwareFileContent(FileInfo info) throws IOException {
//    Document doc = FileDocumentManager.getInstance().getDocument(info.file);
//    if (doc == null || info.project == null) {
//      try {
//        return FileContentImpl.createByFile(info.file, info.project);
//      } catch (FileNotFoundException ignored) {
//        return null;
//      }
//    }
//    PsiFile psi = PsiDocumentManager.getInstance(info.project).getPsiFile(doc);
//    DocumentContent content = FileBasedIndexImpl.findLatestContent(doc, psi);
//    var file = info.file;
//    return FileContentImpl.createByContent(file, () -> {
//      var text = content.getText();
//      return text.toString().getBytes(file.getCharset());
//    }, info.project);
    throw new UnsupportedOperationException();
  }

  public void dispose() {
    myFileElementTypesCache.clear();
    myModCounts.clear();
    myPendingUpdates.clear();
    myProbablyExpensiveUpdates.clear();
    myModificationsInCurrentBatch.clear();
    myStubUpdatingIndexStorage.drop();
  }

  private static @Nullable StubFileElementType determineCurrentFileElementType(IndexedFile indexedFile) {
      if (shouldSkipFile(indexedFile.getFile())) {
          return null;
      }
    StubBuilderType stubBuilderType = StubTreeBuilder.getStubBuilderType(indexedFile, true);
      if (stubBuilderType == null) {
          return null;
      }
//    return stubBuilderType.getBinaryFileStubBuilder().getStubVersion();
    throw new UnsupportedOperationException();
  }

  private @NonNull List<StubFileElementType<?>> determinePreviousFileElementType(int fileId, StubUpdatingIndexStorage index) {
    throw new UnsupportedOperationException();
//    String storedVersion = index.getStoredSubIndexerVersion(fileId);
//      if (storedVersion == null) {
//          return Collections.emptyList();
//      }
//    return myFileElementTypesCache.compute(storedVersion, (__, value) -> {
//        if (value != null) {
//            return value;
//        }
//      List<StubFileElementType<?>> types = StubBuilderType.getStubFileElementTypeFromVersion(storedVersion);
//      if (types.size() > 1) {
//        LOG.error("Cannot distinguish StubFileElementTypes. This might worsen the performance. " +
//                  "Providing unique externalId or adding a distinctive debugName when instantiating StubFileElementTypes can help. " +
//                  "Version: " + storedVersion + " -> " +
//                  ContainerUtil.map(types, t -> {
//                    return t.getClass().getName() + "{" + t.getExternalId() + ";" + t.getDebugName() + ";" + t.getLanguage() + "}";
//                  }));
//      }
//      return types;
//    });
  }

  /**
   * There is no need to process binary files (e.g. .class), so we should just skip them.
   * Their processing might trigger content read which is expensive.
   * Also, we should not process files which weren't indexed by StubIndex yet (or won't be, such as large files).
   */
  private static boolean shouldSkipFile(VirtualFile file) {
    if (((FileBasedIndexImpl)FileBasedIndex.getInstance()).isTooLarge(file, file.getLength(), Collections.emptySet())) {
      return true;
    }
    { // this code snippet is taken from StubTreeBuilder#getStubBuilderType
      FileType fileType = file.getFileType();
      final BinaryFileStubBuilder builder = BinaryFileStubBuilders.INSTANCE.forFileType(fileType);
        if (builder != null) {
            return true;
        }
    }
      if (!StubUpdatingIndex.canHaveStub(file)) {
          return true;
      }
    return false;
  }
}