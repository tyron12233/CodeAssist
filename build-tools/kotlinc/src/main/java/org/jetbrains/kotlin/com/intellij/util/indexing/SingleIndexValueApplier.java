package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.ConcurrencyUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.indexing.UpdatableIndex;

import java.util.function.Supplier;

@ApiStatus.Internal
class SingleIndexValueApplier<FileIndexMetaData> {
  private final CoreFileBasedIndex myIndex;
  @NotNull
  final ID<?, ?> indexId;
  final int inputId;
  private final @Nullable FileIndexMetaData myFileIndexMetaData;
  final long evaluatingIndexValueApplierTime;
  @NotNull final Supplier<Boolean> storageUpdate;
  @NotNull private final String fileInfo;
  private final boolean isMock;

  SingleIndexValueApplier(@NotNull CoreFileBasedIndex index,
                          @NotNull ID<?, ?> indexId,
                          int inputId,
                          @Nullable FileIndexMetaData fileIndexMetaData,
                          @NotNull Supplier<Boolean> update,
                          @NotNull VirtualFile file,
                          @NotNull FileContent currentFC,
                          long evaluatingIndexValueApplierTime) {
    myIndex = index;
    this.indexId = indexId;
    this.inputId = inputId;
    myFileIndexMetaData = fileIndexMetaData;
    this.evaluatingIndexValueApplierTime = evaluatingIndexValueApplierTime;
    storageUpdate = update;
    fileInfo = CoreFileBasedIndex.getFileInfoLogString(inputId, file, currentFC);
    isMock = CoreFileBasedIndex.isMock(currentFC.getFile());
  }

  boolean wasIndexProvidedByExtension() {
//    return storageUpdate instanceof IndexInfrastructureExtensionUpdateComputation &&
//           ((IndexInfrastructureExtensionUpdateComputation)storageUpdate).isIndexProvided();
    return false;
  }

  boolean applyImmediately() {
    return doApply();
  }

  boolean apply() {
    CoreFileBasedIndex.markFileWritingIndexes(inputId);
    try {
      return doApply();
    }
    catch (RuntimeException exception) {
      myIndex.requestIndexRebuildOnException(exception, indexId);
      return false;
    }
    finally {
      CoreFileBasedIndex.unmarkWritingIndexes();
    }
  }

  private boolean doApply() {
    if (myIndex.runUpdateForPersistentData(storageUpdate)) {
      if (myIndex.doTraceStubUpdates(indexId) || myIndex.doTraceIndexUpdates()) {
        CoreFileBasedIndex.LOG.info("index " + indexId + " update finished for " + fileInfo);
      }
      if (!isMock) {
        ConcurrencyUtil.withLock(myIndex.myReadLock, () -> {
          //noinspection unchecked
          UpdatableIndex<?, ?, FileContent, FileIndexMetaData> index =
            (UpdatableIndex<?, ?, FileContent, FileIndexMetaData>)myIndex.getIndex(indexId);
          setIndexedState(index, myFileIndexMetaData, inputId, wasIndexProvidedByExtension());
        });
      }
    }
    return true;
  }

  private static <FileIndexMetaData> void setIndexedState(@NotNull UpdatableIndex<?, ?, FileContent, FileIndexMetaData> index,
                                                          @Nullable FileIndexMetaData fileData,
                                                          int inputId,
                                                          boolean indexWasProvided) {
//    if (index instanceof FileBasedIndexInfrastructureExtensionUpdatableIndex) {
//      //noinspection unchecked
//      ((FileBasedIndexInfrastructureExtensionUpdatableIndex<?, ?, ?, FileIndexMetaData>)index)
//        .setIndexedStateForFileOnFileIndexMetaData(inputId, fileData, indexWasProvided);
//    }
//    else {
      index.setIndexedStateForFileOnFileIndexMetaData(inputId, fileData);
//    }
  }
}