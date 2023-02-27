package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.util.indexing.*;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.MapReduceIndexMappingException;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage.TransientFileContentIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.VfsAwareIndexStorageLayout;


import java.io.IOException;
import java.util.Collections;
import java.util.Map;

public final class StubUpdatingIndexStorage extends TransientFileContentIndex<Integer, SerializedStubTree, StubUpdatingIndexStorage.Data> {
  private static final Logger LOG = Logger.getInstance(StubUpdatingIndexStorage.class);

  private CoreStubIndex myStubIndex;
  @Nullable
  private final CompositeBinaryBuilderMap myCompositeBinaryBuilderMap = FileBasedIndex.USE_IN_MEMORY_INDEX
                                                                        ? null
                                                                        : new CompositeBinaryBuilderMap();
  private final @NonNull SerializationManagerEx mySerializationManager;

  StubUpdatingIndexStorage(@NonNull FileBasedIndexExtension<Integer, SerializedStubTree> extension,
                           @NonNull VfsAwareIndexStorageLayout<Integer, SerializedStubTree> layout,
                           @NonNull SerializationManagerEx serializationManager) throws IOException {
    super(extension, layout);
    mySerializationManager = serializationManager;
  }

  @Override
  protected void doFlush() throws IOException, StorageException {
    final CoreStubIndex stubIndex = getStubIndex();
    try {
      stubIndex.flush();
      mySerializationManager.flushNameStorage();
    }
    finally {
      super.doFlush();
    }
  }

  @NonNull
  private CoreStubIndex getStubIndex() {
    CoreStubIndex index = myStubIndex;
    if (index == null) {
      myStubIndex = index = (CoreStubIndex)StubIndex.getInstance();
    }
    return index;
  }

  @Override
  public @NonNull Computable<Boolean> mapInputAndPrepareUpdate(int inputId, @Nullable FileContent content)
    throws MapReduceIndexMappingException, ProcessCanceledException {
    Computable<Boolean> indexUpdateComputable = super.mapInputAndPrepareUpdate(inputId, content);
    IndexingStampInfo indexingStampInfo = content == null ? null : StubUpdatingIndex.calculateIndexingStamp(content);

    return () -> {
      try {
        Boolean result = indexUpdateComputable.compute();
        if (Boolean.TRUE.equals(result) && !StaleIndexesChecker.isStaleIdDeletion()) {
//          StubTreeLoaderImpl.saveIndexingStampInfo(indexingStampInfo, inputId);
        }
        return result;
      }
      catch (ProcessCanceledException e) {
        LOG.error("ProcessCanceledException is not expected here", e);
        throw e;
      }
    };
  }

  @Override
  public void removeTransientDataForKeys(int inputId, @NonNull InputDataDiffBuilder<Integer, SerializedStubTree> diffBuilder) {
    Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> maps = getStubIndexMaps((StubCumulativeInputDiffBuilder)diffBuilder);

    if (FileBasedIndexEx.DO_TRACE_STUB_INDEX_UPDATE) {
      LOG.info("removing transient data for inputId = " + inputId +
               ", keys = " + ((StubCumulativeInputDiffBuilder)diffBuilder).getKeys() +
               ", data = " + maps);
    }

    super.removeTransientDataForKeys(inputId, diffBuilder);
    removeTransientStubIndexKeys(inputId, maps);
  }

  private static void removeTransientStubIndexKeys(int inputId, @NonNull Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> indexedStubs) {
    CoreStubIndex stubIndex = (CoreStubIndex) StubIndex.getInstance();
    for (StubIndexKey key : indexedStubs.keySet()) {
//      stubIndex.removeTransientDataForFile(key, inputId, indexedStubs.get(key));
    }
  }

  @NonNull
  private static Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> getStubIndexMaps(@NonNull StubCumulativeInputDiffBuilder diffBuilder) {
    SerializedStubTree tree = diffBuilder.getSerializedStubTree();
    return tree == null ? Collections.emptyMap() : tree.getStubIndicesValueMap();
  }

  @Override
  protected void doClear() throws StorageException, IOException {
    final StubIndexImpl stubIndex = (StubIndexImpl)StubIndex.getInstance();
    if (stubIndex != null) {
//      stubIndex.clearAllIndices();
    }
    super.doClear();
  }

  @Override
  protected void doDispose() throws StorageException {
    try {
      super.doDispose();
    }
    finally {
      try {
//        getStubIndex().dispose();
      }
      finally {
        mySerializationManager.performShutdown();
      }
    }
  }

  static class Data extends IndexerIdHolder {
    private final FileType myFileType;

    Data(int indexerId, FileType type) {
      super(indexerId);
      myFileType = type;
    }
  }

  @Override
  public Data getFileIndexMetaData(@NonNull IndexedFile file) {
    IndexerIdHolder data = super.getFileIndexMetaData(file);
    FileType fileType = ProgressManager.getInstance().computeInNonCancelableSection(file::getFileType);
    return new Data(data == null ? -1 : data.indexerId, fileType);
  }

  @Override
  public void setIndexedStateForFileOnFileIndexMetaData(int fileId, @Nullable StubUpdatingIndexStorage.Data fileData) {
    super.setIndexedStateForFileOnFileIndexMetaData(fileId, fileData);
    LOG.assertTrue(fileData != null, "getFileIndexMetaData doesn't return null");
    setBinaryBuilderConfiguration(fileId, fileData);
  }

  @Override
  public void setIndexedStateForFile(int fileId, @NonNull IndexedFile file) {
    super.setIndexedStateForFile(fileId, file);
    setBinaryBuilderConfiguration(fileId, file);
  }

  @Override
  public void setUnindexedStateForFile(int fileId) {
    super.setUnindexedStateForFile(fileId);
    resetBinaryBuilderConfiguration(fileId);
  }

  @Override
  protected FileIndexingState isIndexConfigurationUpToDate(int fileId, @NonNull IndexedFile file) {
      if (myCompositeBinaryBuilderMap == null) {
          return FileIndexingState.UP_TO_DATE;
      }
    try {
      return myCompositeBinaryBuilderMap.isUpToDateState(fileId, file.getFile());
    }
    catch (IOException e) {
      LOG.error(e);
      return FileIndexingState.OUT_DATED;
    }
  }

  @Override
  protected void setIndexConfigurationUpToDate(int fileId, @NonNull IndexedFile file) {
    setBinaryBuilderConfiguration(fileId, file);
  }

  private void setBinaryBuilderConfiguration(int fileId, @NonNull IndexedFile file) {
    if (myCompositeBinaryBuilderMap != null) {
      try {
        myCompositeBinaryBuilderMap.persistState(fileId, file.getFile());
      }
      catch (IOException e) {
        LOG.error(e);
      }
    }
  }

  private void setBinaryBuilderConfiguration(int fileId, @NonNull Data fileData) {
    if (myCompositeBinaryBuilderMap != null) {
      try {
        myCompositeBinaryBuilderMap.persistState(fileId, fileData.myFileType);
      }
      catch (IOException e) {
        LOG.error(e);
      }
    }
  }

  private void resetBinaryBuilderConfiguration(int fileId) {
    if (myCompositeBinaryBuilderMap != null) {
      try {
        myCompositeBinaryBuilderMap.resetPersistedState(fileId);
      }
      catch (IOException e) {
        LOG.error(e);
      }
    }
  }

//  @ApiStatus.Internal
  DataIndexer<Integer, SerializedStubTree, FileContent> getIndexer() {
    return myIndexer;
  }
}