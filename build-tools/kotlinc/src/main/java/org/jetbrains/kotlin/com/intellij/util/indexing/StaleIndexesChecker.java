package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubUpdatingIndex;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntSet;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntSets;

import java.util.function.IntConsumer;

public final class StaleIndexesChecker {
  private static final Logger LOG = Logger.getInstance(StaleIndexesChecker.class);
  private static final ThreadLocal<Boolean> IS_IN_STALE_IDS_DELETION = new ThreadLocal<>();

  public static boolean isStaleIdDeletion() {
    return IS_IN_STALE_IDS_DELETION.get() == Boolean.TRUE;
  }

  static @NonNull IntSet checkIndexForStaleRecords(@NonNull UpdatableIndex<?, ?, FileContent, ?> index,
                                                   IntSet knownStaleIds,
                                                   boolean onStartup) throws StorageException {
    if (!ApplicationManager.getApplication().isInternal() && !ApplicationManager.getApplication().isEAP()) {
      return IntSets.EMPTY_SET;
    }
    IndexExtension<?, ?, FileContent> extension = index.getExtension();
    IndexId<?, ?> indexId = extension.getName();
    LOG.assertTrue(indexId.equals(StubUpdatingIndex.INDEX_ID));
    LOG.assertTrue(extension instanceof SingleEntryFileBasedIndexExtension);

    Int2ObjectMap<String> staleFiles = new Int2ObjectOpenHashMap<>();
//    for (int freeRecord : onStartup ? FSRecords.getRemainFreeRecords() : FSRecords.getNewFreeRecords()) {
//      if (knownStaleIds.contains(freeRecord)) {
//        continue;
//      }
//      Map<?, ?> dataAsMap = index.getIndexedFileData(freeRecord);
//      Object data = ContainerUtil.getFirstItem(dataAsMap.values());
//      if (data != null) {
//        String name;
//        name = getStaleRecordOrExceptionMessage(freeRecord);
//        staleFiles.put(freeRecord, name);
//      }
//    }

    if (!staleFiles.isEmpty()) {
      if (ApplicationManager.getApplication().isUnitTestMode() && onStartup) {
        // report it as late as possible, give a chance for test to fail by another reason
        Disposer.register(ApplicationManager.getApplication(), () -> {
          LOG.error(getStaleInputIdsMessage(staleFiles, indexId));
        });
      }
      else {
        LOG.error(getStaleInputIdsMessage(staleFiles, indexId));
      }
    }

    return staleFiles.keySet();
  }

  static String getStaleRecordOrExceptionMessage(int record) {
    try {
      return getRecordPath(record);
    }
    catch (Exception e) {
      return e.getMessage();
    }
  }

  private static String getRecordPath(int record) {
//    StringBuilder name = new StringBuilder(FSRecords.getName(record));
//    int parent = FSRecords.getParent(record);
//    while (parent > 0) {
//      name.insert(0, FSRecords.getName(parent) + "/");
//      parent = FSRecords.getParent(parent);
//    }
//    return name.toString();
    throw new UnsupportedOperationException();
  }

  static void clearStaleIndexes(@NonNull IntSet staleIds) {
    IS_IN_STALE_IDS_DELETION.set(Boolean.TRUE);
    boolean unitTest = ApplicationManager.getApplication().isUnitTestMode();
    try {
      ProgressManager.getInstance().executeNonCancelableSection(() -> {
        staleIds.forEach((IntConsumer) (staleId) -> {
          if (unitTest) {
            LOG.info("clearing stale id = " + staleId + ", path =  " + getRecordPath(staleId));
          }
          clearStaleIndexesForId(staleId);
        });
      });
    }
    finally {
      IS_IN_STALE_IDS_DELETION.remove();
    }
  }

  static void clearStaleIndexesForId(int staleInputId) {
//    FileBasedIndexImpl fileBasedIndex = (FileBasedIndexImpl)FileBasedIndex.getInstance();
//    Collection<ID<?, ?>> indexIds = fileBasedIndex.getRegisteredIndexes().getState().getIndexIDs();
//    fileBasedIndex.removeFileDataFromIndices(indexIds, staleInputId, null);
    throw new UnsupportedOperationException();
  }

  @NonNull
  private static String getStaleInputIdsMessage(Int2ObjectMap<String> staleTrees, IndexId<?, ?> indexId) {
    return "`" + indexId + "` index contains several stale file ids (size = "
           + staleTrees.size()
           + "). Ids & paths: "
           + StringUtil.first(staleTrees.toString(), 300, true)
           + ".";
  }
}