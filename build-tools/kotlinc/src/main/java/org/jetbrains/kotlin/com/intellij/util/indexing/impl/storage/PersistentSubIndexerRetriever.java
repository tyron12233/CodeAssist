package org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.FileAttribute;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent.FSRecords;
import org.jetbrains.kotlin.com.intellij.util.indexing.CompositeDataIndexer;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIndexingState;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexInfrastructure;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexedFile;
import org.jetbrains.kotlin.com.intellij.util.io.DataInputOutputUtil;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class PersistentSubIndexerRetriever<SubIndexerType, SubIndexerVersion> implements Closeable {
    private static final String INDEXED_VERSIONS = "indexed_versions";
    private static final int UNINDEXED_STATE = -2;
    private static final int NULL_SUB_INDEXER = -3;

    @NotNull
    private final PersistentSubIndexerVersionEnumerator<SubIndexerVersion> myPersistentVersionEnumerator;
    @NotNull
    private final FileAttribute myFileAttribute;
    @NotNull
    private final CompositeDataIndexer<?, ?, SubIndexerType, SubIndexerVersion> myIndexer;

    public PersistentSubIndexerRetriever(@NotNull ID<?, ?> id,
                                         int indexVersion,
                                         @NotNull CompositeDataIndexer<?, ?, SubIndexerType, SubIndexerVersion> indexer) throws IOException {
        this(IndexInfrastructure.getIndexRootDir(id), id.getName(), indexVersion, indexer);
    }

    @TestOnly
    PersistentSubIndexerRetriever(@NotNull Path root,
                                  @NotNull String indexName,
                                  int indexVersion,
                                  @NotNull CompositeDataIndexer<?, ?, SubIndexerType, SubIndexerVersion> indexer) throws IOException {
        Path versionMapRoot = root.resolve(versionMapRoot());
        myFileAttribute = getFileAttribute(indexName, indexVersion);
        myIndexer = indexer;
        myPersistentVersionEnumerator = new PersistentSubIndexerVersionEnumerator<>(
                versionMapRoot.resolve(INDEXED_VERSIONS).toFile(),
                indexer.getSubIndexerVersionDescriptor());
    }

    public void clear() throws IOException {
        myPersistentVersionEnumerator.clear();
    }

    @Override
    public void close() throws IOException {
        myPersistentVersionEnumerator.close();
    }

    public void flush() throws IOException {
        myPersistentVersionEnumerator.flush();
    }

    private static Path versionMapRoot() {
        return Paths.get(".perFileVersion", INDEXED_VERSIONS);
    }

    public void setIndexedState(int fileId, @NotNull IndexedFile file) throws IOException {
        int indexerId = ProgressManager.getInstance().computeInNonCancelableSection(() -> getFileIndexerId(file));
        setFileIndexerId(fileId, indexerId);
    }

    public void setUnindexedState(int fileId) throws IOException {
        setFileIndexerId(fileId, UNINDEXED_STATE);
    }

    public void setFileIndexerId(int fileId, int indexerId) throws IOException {
        try (DataOutputStream stream = FSRecords.writeAttribute(fileId, myFileAttribute)) {
            DataInputOutputUtil.writeINT(stream, indexerId);
        }
    }

    /**
     * @return stored file indexer id. value < 0 means that no id is available for specified file
     */
    public int getStoredFileIndexerId(int fileId) throws IOException {
        try (DataInputStream stream = FSRecords.readAttributeWithLock(fileId, myFileAttribute)) {
            if (stream == null) return UNINDEXED_STATE;
            return DataInputOutputUtil.readINT(stream);
        }
    }

    public FileIndexingState getSubIndexerState(int fileId, @NotNull IndexedFile file) throws IOException {
        try (DataInputStream stream = FSRecords.readAttributeWithLock(fileId, myFileAttribute)) {
            if (stream != null) {
                int currentIndexedVersion = DataInputOutputUtil.readINT(stream);
                if (currentIndexedVersion == UNINDEXED_STATE) {
                    return FileIndexingState.NOT_INDEXED;
                }
                int actualVersion = getFileIndexerId(file);
                return actualVersion == currentIndexedVersion ? FileIndexingState.UP_TO_DATE : FileIndexingState.OUT_DATED;
            }
            return FileIndexingState.NOT_INDEXED;
        }
    }

    public int getFileIndexerId(@NotNull IndexedFile file) throws IOException {
        SubIndexerVersion version = getVersion(file);
        if (version == null) return NULL_SUB_INDEXER;
        return myPersistentVersionEnumerator.enumerate(version);
    }

    public SubIndexerVersion getVersionByIndexerId(int indexerId) throws IOException {
        return myPersistentVersionEnumerator.valueOf(indexerId);
    }

    @Nullable
    public SubIndexerVersion getVersion(@NotNull IndexedFile file) {
        SubIndexerType type = myIndexer.calculateSubIndexer(file);
        if (type == null) return null;
        return myIndexer.getSubIndexerVersion(type);
    }

    private static final Map<Pair<String, Integer>, FileAttribute> ourAttributes = new HashMap<>();

    private static FileAttribute getFileAttribute(String name, int version) {
        synchronized (ourAttributes) {
            return ourAttributes.computeIfAbsent(new Pair<>(name, version), __ -> new FileAttribute(name + ".index.version", version, false));
        }
    }
}
