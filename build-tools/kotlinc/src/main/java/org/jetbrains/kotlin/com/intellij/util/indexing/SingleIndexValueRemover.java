package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.ConcurrencyUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexImpl;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.indexing.RebuildStatus;
import org.jetbrains.kotlin.com.intellij.util.indexing.UpdatableIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.MapReduceIndexMappingException;
import org.jetbrains.kotlin.com.intellij.util.indexing.snapshot.SnapshotInputMappingException;

import java.util.function.Supplier;

@ApiStatus.Internal
class SingleIndexValueRemover {
  private final CoreFileBasedIndex myIndexImpl;
  final @NotNull ID<?, ?> indexId;
  private final VirtualFile file;
  private final int inputId;
  private final @Nullable String fileInfo;
  private final @Nullable String filePath;
  private final boolean isWritingValuesSeparately;
  long evaluatingValueRemoverTime;

  SingleIndexValueRemover(CoreFileBasedIndex indexImpl, @NotNull ID<?, ?> indexId,
                          @Nullable VirtualFile file,
                          @Nullable FileContent fileContent,
                          int inputId,
                          boolean isWritingValuesSeparately) {
    myIndexImpl = indexImpl;
    this.indexId = indexId;
    this.file = file;
    this.inputId = inputId;
    this.fileInfo = CoreFileBasedIndex.getFileInfoLogString(inputId, file, fileContent);
    this.filePath = file == null ? (fileContent == null ? null : fileContent.getFile().getPath()) : file.getPath();
    this.isWritingValuesSeparately = isWritingValuesSeparately;
  }

  /**
   * @return false in case index update is not necessary or the update has failed
   */
  boolean remove() {
    if (!RebuildStatus.isOk(indexId) && !myIndexImpl.myIsUnitTestMode) {
      return false; // the index is scheduled for rebuild, no need to update
    }
    myIndexImpl.increaseLocalModCount();

    UpdatableIndex<?, ?, FileContent, ?> index = myIndexImpl.getIndex(indexId);

    if (isWritingValuesSeparately) {
      CoreFileBasedIndex.markFileWritingIndexes(inputId);
    }
    else {
      CoreFileBasedIndex.markFileIndexed(file, null);
    }
    try {
      Supplier<Boolean> storageUpdate;
      long startTime = System.nanoTime();
      try {
        storageUpdate = index.mapInputAndPrepareUpdate(inputId, null);
      }
      catch (MapReduceIndexMappingException e) {
        Throwable cause = e.getCause();
        if (cause instanceof SnapshotInputMappingException) {
          myIndexImpl.requestRebuild(indexId, e);
          return false;
        }
//        BrokenIndexingDiagnostics.INSTANCE.getExceptionListener().onFileIndexMappingFailed(inputId, null, null, indexId, e);
        return false;
      }
      finally {
        this.evaluatingValueRemoverTime = System.nanoTime() - startTime;
      }

      if (myIndexImpl.runUpdateForPersistentData(storageUpdate)) {
        if (myIndexImpl.doTraceStubUpdates(indexId) || myIndexImpl.doTraceIndexUpdates()) {
          FileBasedIndexImpl.LOG.info("index " + indexId + " deletion finished for " + fileInfo);
        }
        ConcurrencyUtil.withLock(myIndexImpl.myReadLock, () -> {
          index.setUnindexedStateForFile(inputId);
        });
      }
      return true;
    }
    catch (RuntimeException exception) {
      myIndexImpl.requestIndexRebuildOnException(exception, indexId);
      return false;
    }
    finally {
      if (isWritingValuesSeparately) {
        CoreFileBasedIndex.unmarkWritingIndexes();
      }
      else {
        CoreFileBasedIndex.unmarkBeingIndexed();
      }
    }
  }
}