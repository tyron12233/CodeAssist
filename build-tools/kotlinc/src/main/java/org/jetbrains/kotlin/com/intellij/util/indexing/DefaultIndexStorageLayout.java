package org.jetbrains.kotlin.com.intellij.util.indexing;


import static org.jetbrains.kotlin.com.intellij.util.indexing.CoreStubIndex.deleteWithRenaming;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.EmptyForwardIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndexAccessor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.MapForwardIndexAccessor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.PersistentMapBasedForwardIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.SingleEntryIndexForwardIndexAccessor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage.VfsAwareMapIndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage.VfsAwareMapReduceIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.VfsAwareIndexStorageLayout;
import org.jetbrains.kotlin.com.intellij.util.io.PagedFileStorage;
import org.jetbrains.kotlin.com.intellij.util.io.StorageLockContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public class DefaultIndexStorageLayout {

    public static <K, V>VfsAwareIndexStorage<K, V> createIndexStorage(FileBasedIndexExtension<K, V> extension, StorageLockContext storageLockContext) throws IOException {
        Path indexFile = IndexInfrastructure.getStorageFile(extension.getName());
        return new VfsAwareMapIndexStorage<K, V>(
                indexFile,
                extension.getKeyDescriptor(),
                extension.getValueExternalizer(),
                extension.getCacheSize(),
                extension.keyIsUniqueForIndexedFile(),
                extension.traceKeyHashToVirtualFileMapping(),
                extension.enableWal()
        ) {
            @Override
            protected void initMapAndCache() throws IOException {
                assert PagedFileStorage.THREAD_LOCAL_STORAGE_LOCK_CONTEXT.get() == null;
                try {
                    super.initMapAndCache();
                } finally {
                    PagedFileStorage.THREAD_LOCAL_STORAGE_LOCK_CONTEXT.remove();
                }
            }
        };
    }

    public static <K, V> VfsAwareIndexStorageLayout<K, V> getLayout(FileBasedIndexExtension<K, V> extension,
                                                                    boolean contentHashesEnumeratorOk) {
        if (extension instanceof SingleEntryFileBasedIndexExtension) {
            return new SingleEntryStorageLayout<>(extension);
        }
        if (VfsAwareMapReduceIndex.hasSnapshotMapping(extension)) {
            throw new UnsupportedOperationException("Snapshots not yet supported");
        }
        return new DefaultStorageLayout<>(extension);
    }

    private static class DefaultStorageLayout<K, V> implements VfsAwareIndexStorageLayout<K, V> {

        private final StorageLockContext storageLockContext = new StorageLockContext(false, true);

        private final FileBasedIndexExtension<K, V> extension;

        public DefaultStorageLayout(FileBasedIndexExtension<K, V> extension) {
            this.extension = extension;
        }

        @NonNull
        @Override
        public IndexStorage<K, V> openIndexStorage() throws IOException {
            return createIndexStorage(extension, storageLockContext);
        }

        @Nullable
        @Override
        public ForwardIndex openForwardIndex() throws IOException {
            Path indexStorageFile =
                    IndexInfrastructure.getInputIndexStorageFile(extension.getName());
            return new PersistentMapBasedForwardIndex(indexStorageFile,
                    false,
                    false,
                    storageLockContext);
        }

        @Nullable
        @Override
        public ForwardIndexAccessor<K, V> getForwardIndexAccessor() throws IOException {
            return new MapForwardIndexAccessor<>(new InputMapExternalizer<>(extension));
        }

        @Override
        public void clearIndexData() {
            deleteIndexDirectory(extension);
        }

        private void deleteIndexDirectory(FileBasedIndexExtension<K, V> extension) {
            try {
                deleteWithRenaming(IndexInfrastructure.getIndexRootDir(extension.getName()).toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static class SingleEntryStorageLayout<K, V> implements VfsAwareIndexStorageLayout<K, V> {

        private final StorageLockContext storageLockContext = newStorageLockContext();
        private final FileBasedIndexExtension<K, V> extension;

        public SingleEntryStorageLayout(FileBasedIndexExtension<K, V> extension) {
            this.extension = extension;
        }

        @NonNull
        @Override
        public IndexStorage<K, V> openIndexStorage() throws IOException {
            return createIndexStorage(extension, storageLockContext);
        }

        @Nullable
        @Override
        public ForwardIndex openForwardIndex() throws IOException {
            return new EmptyForwardIndex();
        }

        @SuppressWarnings("unchecked")
        @Nullable
        @Override
        public ForwardIndexAccessor<K, V> getForwardIndexAccessor() throws IOException {
            return ((ForwardIndexAccessor<K, V>) new SingleEntryIndexForwardIndexAccessor<>(((IndexExtension<Integer, V, ?>) extension)));
        }

        @Override
        public void clearIndexData() {
            deleteIndexDirectory(extension);
        }
    }

    private static void deleteIndexDirectory(FileBasedIndexExtension<?, ?> extension) {
        try {
            FileUtil.deleteWithRenaming(IndexInfrastructure.getIndexRootDir(extension.getName()).toFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static StorageLockContext newStorageLockContext() {
        return new StorageLockContext(false, true);
    }
}
