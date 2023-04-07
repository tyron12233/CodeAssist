package org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.diagnostic.PluginException;
import org.jetbrains.kotlin.com.intellij.openapi.application.Application;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginId;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.indexing.CompositeDataIndexer;
import org.jetbrains.kotlin.com.intellij.util.indexing.DataIndexer;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContentImpl;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIdStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIndexingState;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexDataInitializer;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexedFile;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexingStamp;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexDebugProperties;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputData;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.ValueSerializationProblemReporter;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.AbstractMapForwardIndexAccessor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndexAccessor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.IntForwardIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.snapshot.SnapshotInputMappingException;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.MapReduceIndexBase;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.SnapshotInputMappingIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.VfsAwareIndexStorageLayout;
import org.jetbrains.kotlin.com.intellij.util.io.IOUtil;

import java.io.IOException;
import java.util.Map;

/**
 * @author Eugene Zhuravlev
 */
public class VfsAwareMapReduceIndex<Key, Value,
        FileCachedData extends VfsAwareMapReduceIndex.IndexerIdHolder> extends MapReduceIndexBase<Key, Value, FileCachedData> {
    private static final Logger LOG = Logger.getInstance(VfsAwareMapReduceIndex.class);

    //  @ApiStatus.Internal
    public static final int VERSION = 0;

    static {
        final Application app = ApplicationManager.getApplication();

        if (!IndexDebugProperties.DEBUG) {
            IndexDebugProperties.DEBUG = (app.isEAP() || app.isInternal());
        }

        if (!IndexDebugProperties.IS_UNIT_TEST_MODE) {
            IndexDebugProperties.IS_UNIT_TEST_MODE = app.isUnitTestMode();
        }
    }

    @SuppressWarnings("rawtypes")
    private final PersistentSubIndexerRetriever mySubIndexerRetriever;
    private final SnapshotInputMappingIndex<Key, Value, FileContent> mySnapshotInputMappings;
    private final boolean myUpdateMappings;

    public VfsAwareMapReduceIndex(@NonNull FileBasedIndexExtension<Key, Value> extension,
                                  @NonNull VfsAwareIndexStorageLayout<Key, Value> indexStorageLayout) throws IOException {
        this(extension,
                indexStorageLayout::openIndexStorage,
                indexStorageLayout::openForwardIndex,
                indexStorageLayout.getForwardIndexAccessor(),
                indexStorageLayout::createOrClearSnapshotInputMappings);
    }

    protected VfsAwareMapReduceIndex(@NonNull FileBasedIndexExtension<Key, Value> extension,
                                     @NonNull ThrowableComputable<? extends IndexStorage<Key,
                                             Value>, ? extends IOException> storage,
                                     @Nullable ThrowableComputable<? extends ForwardIndex, ?
                                             extends IOException> forwardIndexMap,
                                     @Nullable ForwardIndexAccessor<Key, Value> forwardIndexAccessor,
                                     @Nullable ThrowableComputable<?
                                             extends SnapshotInputMappingIndex<Key, Value,
                                             FileContent>, ? extends IOException> snapshotInputMappings) throws IOException {
        super(extension, storage, forwardIndexMap, forwardIndexAccessor);
        SnapshotInputMappingIndex<Key, Value, FileContent> inputMappings;
        try {
            inputMappings = snapshotInputMappings == null ? null : snapshotInputMappings.compute();
        } catch (IOException e) {
            tryDispose();
            throw e;
        }
        mySnapshotInputMappings = inputMappings;
        myUpdateMappings = mySnapshotInputMappings != null;

        if (inputMappings != null) {
            @NonNull IndexStorage<Key, Value> backendStorage = getStorage();
            if (backendStorage instanceof TransientChangesIndexStorage) {
                backendStorage =
                        ((TransientChangesIndexStorage<Key, Value>) backendStorage).getBackendStorage();
            }
//            if (backendStorage instanceof SnapshotSingleValueIndexStorage) {
//                LOG.assertTrue(forwardIndexMap instanceof IntForwardIndex);
//                ((SnapshotSingleValueIndexStorage<Key, Value>) backendStorage).init((SnapshotInputMappings<Key, Value>) inputMappings,
//                        ((IntForwardIndex) forwardIndexMap));
//            }
        }
        if (isCompositeIndexer(myIndexer)) {
            // noinspection unchecked,rawtypes
            mySubIndexerRetriever = new PersistentSubIndexerRetriever((ID) myIndexId,
                    extension.getVersion(),
                    (CompositeDataIndexer) myIndexer);
//                if (inputMappings instanceof SnapshotInputMappings) {
//                    ((SnapshotInputMappings<?, ?>) inputMappings).setSubIndexerRetriever(
//                            mySubIndexerRetriever);
//                }
        } else {
            mySubIndexerRetriever = null;
        }
    }

    public void resetSnapshotInputMappingsStatistics() {
//        if (mySnapshotInputMappings instanceof SnapshotInputMappings<?, ?>) {
//            ((SnapshotInputMappings<?, ?>) mySnapshotInputMappings).resetStatistics();
//        }
    }

//    public @Nullable SnapshotInputMappingsStatistics dumpSnapshotInputMappingsStatistics() {
//        if (mySnapshotInputMappings instanceof SnapshotInputMappings<?, ?>) {
//            return ((SnapshotInputMappings<?, ?>) mySnapshotInputMappings).dumpStatistics();
//        }
//        return null;
//    }

    @Override
    protected Logger getLogger() {
        return LOG;
    }

    public static boolean isCompositeIndexer(@NonNull DataIndexer<?, ?, ?> indexer) {
        return indexer instanceof CompositeDataIndexer && !FileBasedIndex.USE_IN_MEMORY_INDEX;
    }

    public static <Key, Value> boolean hasSnapshotMapping(@NonNull IndexExtension<Key, Value, ?> indexExtension) {
        //noinspection unchecked
        return indexExtension instanceof FileBasedIndexExtension &&
               ((FileBasedIndexExtension<Key, Value>) indexExtension).hasSnapshotMapping() &&
               FileBasedIndex.ourSnapshotMappingsEnabled &&
               !FileBasedIndex.USE_IN_MEMORY_INDEX;
    }

    @Override
    protected void checkNonCancellableSection() {
        LOG.assertTrue(ProgressManager.getInstance().isInNonCancelableSection());
    }

    @NonNull
    @Override
    protected final InputData<Key, Value> mapInput(int inputId, @Nullable FileContent content) {
        InputData<Key, Value> data;
        boolean containsSnapshotData = true;
        boolean isPhysical = content instanceof FileContentImpl
                             &&
                             !((FileContentImpl) content).isTransientContent();
        if (mySnapshotInputMappings != null && isPhysical) {
            try {
                data = mySnapshotInputMappings.readData(content);
                if (data != null) {
                    return data;
                } else {
                    containsSnapshotData = !myUpdateMappings;
                }
            } catch (IOException e) {
                throw new SnapshotInputMappingException(e);
            }
        }
        data = super.mapInput(inputId, content);
        if (!containsSnapshotData) {
//            try {
//                return ((UpdatableSnapshotInputMappingIndex<Key, Value, FileContent>) mySnapshotInputMappings).putData(
//                        content,
//                        data);
//            } catch (IOException e) {
//                throw new SnapshotInputMappingException(e);
//            }
        }
        return data;
    }

    @NonNull
    public InputDataDiffBuilder<Key, Value> getKeysDiffBuilder(int inputId,
                                                               @NonNull Map<Key, Value> keysAndValues) throws IOException {
        return ((AbstractMapForwardIndexAccessor<Key, Value, ?>) getForwardIndexAccessor()).createDiffBuilderByMap(
                inputId,
                keysAndValues);
    }

    public static class IndexerIdHolder {
        public int indexerId;

        public IndexerIdHolder(int indexerId) {
            this.indexerId = indexerId;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public FileCachedData getFileIndexMetaData(@NonNull IndexedFile file) {
        if (mySubIndexerRetriever != null) {
            try {
                IndexerIdHolder holder = ProgressManager.getInstance()
                        .computeInNonCancelableSection(() -> new IndexerIdHolder(mySubIndexerRetriever.getFileIndexerId(file)));
                LOG.assertTrue(holder != null,
                        "getFileIndexMetaData() shouldn't have returned null in " +
                        getClass() +
                        ", " +
                        myIndexId.getName());
                return (FileCachedData) holder;
            } catch (IOException e) {
                LOG.error(e);
                // Index would be rebuilt, and exception would be logged with INFO severity
                // in com.intellij.util.indexing.FileBasedIndexImpl.requestIndexRebuildOnException
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    /**
     * @return value < 0 means that no sub indexer id corresponds to the specified file
     */
    protected int getStoredFileSubIndexerId(int fileId) {
        if (mySubIndexerRetriever == null) {
            throw new IllegalStateException("not a composite indexer");
        }
        try {
            return mySubIndexerRetriever.getStoredFileIndexerId(fileId);
        } catch (IOException e) {
            LOG.error(e);
            return -4;
        }
    }

    public <SubIndexerVersion> SubIndexerVersion getStoredSubIndexerVersion(int fileId) {
        int indexerId = getStoredFileSubIndexerId(fileId);
        if (indexerId < 0) {
            return null;
        }
        try {
            return (SubIndexerVersion) mySubIndexerRetriever.getVersionByIndexerId(indexerId);
        } catch (IOException e) {
            LOG.error(e);
            return null;
        }
    }

    @Override
    public void setIndexedStateForFileOnFileIndexMetaData(int fileId,
                                                          @Nullable FileCachedData fileData) {
        IndexingStamp.setFileIndexedStateCurrent(fileId, (ID<?, ?>) myIndexId);
        if (mySubIndexerRetriever != null) {
            LOG.assertTrue(fileData != null,
                    "getFileIndexMetaData() shouldn't have returned null in " +
                    getClass() +
                    ", " +
                    myIndexId.getName());
            try {
                mySubIndexerRetriever.setFileIndexerId(fileId, fileData.indexerId);
            } catch (IOException e) {
                LOG.error(e);
            }
        }
    }

    @Override
    public void setIndexedStateForFile(int fileId, @NonNull IndexedFile file) {
        IndexingStamp.setFileIndexedStateCurrent(fileId, (ID<?, ?>) myIndexId);
        if (mySubIndexerRetriever != null) {
            try {
                mySubIndexerRetriever.setIndexedState(fileId, file);
            } catch (IOException e) {
                LOG.error(e);
            }
        }
    }

    @Override
    public void invalidateIndexedStateForFile(int fileId) {
        IndexingStamp.setFileIndexedStateOutdated(fileId, (ID<?, ?>) myIndexId);
    }

    @Override
    public void setUnindexedStateForFile(int fileId) {
        IndexingStamp.setFileIndexedStateUnindexed(fileId, (ID<?, ?>) myIndexId);
        if (mySubIndexerRetriever != null) {
            try {
                mySubIndexerRetriever.setUnindexedState(fileId);
            } catch (IOException e) {
                LOG.error(e);
            }
        }
    }

    @Override
    public @NonNull FileIndexingState getIndexingStateForFile(int fileId,
                                                              @NonNull IndexedFile file) {
        FileIndexingState baseState =
                IndexingStamp.isFileIndexedStateCurrent(fileId, (ID<?, ?>) myIndexId);
        if (baseState != FileIndexingState.UP_TO_DATE) {
            return baseState;
        }
        if (mySubIndexerRetriever == null) {
            return FileIndexingState.UP_TO_DATE;
        }
        if (!(file instanceof FileContent)) {
            if (((CompositeDataIndexer<?, ?, ?, ?>) myIndexer).requiresContentForSubIndexerEvaluation(
                    file)) {
                FileIndexingState indexConfigurationState =
                        isIndexConfigurationUpToDate(fileId, file);
                // baseState == UP_TO_DATE => no need to reindex this file
                return indexConfigurationState ==
                       FileIndexingState.OUT_DATED ? FileIndexingState.OUT_DATED :
                        FileIndexingState.UP_TO_DATE;
            }
        }
        try {
            FileIndexingState subIndexerState =
                    mySubIndexerRetriever.getSubIndexerState(fileId, file);
            if (subIndexerState == FileIndexingState.UP_TO_DATE) {
                if (file instanceof FileContent &&
                    ((CompositeDataIndexer<?, ?, ?, ?>) myIndexer).requiresContentForSubIndexerEvaluation(
                            file)) {
                    setIndexConfigurationUpToDate(fileId, file);
                }
                return FileIndexingState.UP_TO_DATE;
            }
            if (subIndexerState == FileIndexingState.NOT_INDEXED) {
                // baseState == UP_TO_DATE => no need to reindex this file
                return FileIndexingState.UP_TO_DATE;
            }
            return subIndexerState;
        } catch (IOException e) {
            LOG.error(e);
            return FileIndexingState.OUT_DATED;
        }
    }

    protected FileIndexingState isIndexConfigurationUpToDate(int fileId,
                                                             @NonNull IndexedFile file) {
        return FileIndexingState.OUT_DATED;
    }

    protected void setIndexConfigurationUpToDate(int fileId, @NonNull IndexedFile file) {
    }

    @Override
    protected void requestRebuild(@NonNull Throwable ex) {
        Runnable action =
                () -> FileBasedIndex.getInstance().requestRebuild((ID<?, ?>) myIndexId, ex);
        Application app = ApplicationManager.getApplication();
        if (app.isUnitTestMode()) {
            // avoid deadlock due to synchronous update in DumbServiceImpl#queueTask
            app.invokeLater(action);
        } else if (app.isReadAccessAllowed()) {
            IndexDataInitializer.submitGenesisTask(() -> {
                action.run();
                return null;
            });
        } else {
            action.run();
        }
    }

    @Override
    protected @NonNull ValueSerializationProblemReporter getSerializationProblemReporter() {
        return problem -> {
            PluginId pluginId = ((ID<?, ?>) myIndexId).getPluginId();
            if (pluginId != null) {
                LOG.error(new PluginException(problem, pluginId));
            } else {
                LOG.error(problem);
            }
        };
    }

    @Override
    protected void doClear() throws StorageException, IOException {
        super.doClear();
        if (mySnapshotInputMappings != null && myUpdateMappings) {
            //                ((UpdatableSnapshotInputMappingIndex<Key, Value, FileContent>) mySnapshotInputMappings).clear();
        }
        if (mySubIndexerRetriever != null) {
            try {
                mySubIndexerRetriever.clear();
            } catch (IOException e) {
                LOG.error(e);
            }
        }
    }

    @Override
    protected void doFlush() throws IOException, StorageException {
        super.doFlush();
        if (mySnapshotInputMappings != null && myUpdateMappings) {
//            ((UpdatableSnapshotInputMappingIndex<Key, Value, FileContent>) mySnapshotInputMappings).flush();
        }
        if (mySubIndexerRetriever != null) {
            mySubIndexerRetriever.flush();
        }
    }

    @Override
    protected void doDispose() throws StorageException {
        try {
            super.doDispose();
        } finally {
            IOUtil.closeSafe(LOG, mySnapshotInputMappings, mySubIndexerRetriever);
        }
    }
}