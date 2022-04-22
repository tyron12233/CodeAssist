package com.tyron.builder.caching.local.internal;

import com.google.common.io.Closer;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.file.FileAccessTracker;
import com.tyron.builder.internal.resource.local.LocallyAvailableResource;
import com.tyron.builder.internal.resource.local.PathKeyFileStore;
import com.tyron.builder.util.internal.GFileUtils;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.caching.BuildCacheEntryReader;
import com.tyron.builder.caching.BuildCacheEntryWriter;
import com.tyron.builder.caching.BuildCacheException;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.BuildCacheService;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DirectoryBuildCacheService implements LocalBuildCacheService, BuildCacheService {

    private final PathKeyFileStore fileStore;
    private final PersistentCache persistentCache;
    private final BuildCacheTempFileStore tempFileStore;
    private final FileAccessTracker fileAccessTracker;
    private final String failedFileSuffix;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public DirectoryBuildCacheService(PathKeyFileStore fileStore, PersistentCache persistentCache, BuildCacheTempFileStore tempFileStore, FileAccessTracker fileAccessTracker, String failedFileSuffix) {
        this.fileStore = fileStore;
        this.persistentCache = persistentCache;
        this.tempFileStore = tempFileStore;
        this.fileAccessTracker = fileAccessTracker;
        this.failedFileSuffix = failedFileSuffix;
    }

    private static class LoadAction implements Action<File> {
        private final BuildCacheEntryReader reader;
        boolean loaded;

        private LoadAction(BuildCacheEntryReader reader) {
            this.reader = reader;
        }

        @Override
        public void execute(@NotNull File file) {
            try {
                Closer closer = Closer.create();
                FileInputStream stream = closer.register(new FileInputStream(file));
                try {
                    reader.readFrom(stream);
                    loaded = true;
                } finally {
                    closer.close();
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    @Override
    public boolean load(final BuildCacheKey key, final BuildCacheEntryReader reader) throws BuildCacheException {
        LoadAction loadAction = new LoadAction(reader);
        loadLocally(key, loadAction);
        return loadAction.loaded;
    }

    @Override
    public void loadLocally(final BuildCacheKey key, final Action<? super File> reader) {
        // We need to lock other processes out here because garbage collection can be under way in another process
        persistentCache.withFileLock(() -> {
            lock.readLock().lock();
            try {
                loadInsideLock(key, reader);
            } finally {
                lock.readLock().unlock();
            }
        });
    }

    private void loadInsideLock(BuildCacheKey key, Action<? super File> reader) {
        LocallyAvailableResource resource = fileStore.get(key.getHashCode());
        if (resource == null) {
            return;
        }

        File file = resource.getFile();
        fileAccessTracker.markAccessed(file);

        try {
            reader.execute(file);
        } catch (Exception e) {
            // Try to move the file out of the way in case its permanently corrupt
            // Don't delete, so that it can be potentially used for debugging
            File failedFile = new File(file.getAbsolutePath() + failedFileSuffix);
            GFileUtils.deleteQuietly(failedFile);
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(failedFile);

            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public void store(final BuildCacheKey key, final BuildCacheEntryWriter result) throws BuildCacheException {
        tempFileStore.withTempFile(key, file -> {
            try {
                Closer closer = Closer.create();
                try {
                    result.writeTo(closer.register(new FileOutputStream(file)));
                } catch (Exception e) {
                    throw closer.rethrow(e);
                } finally {
                    closer.close();
                }
            } catch (IOException ex) {
                throw UncheckedException.throwAsUncheckedException(ex);
            }

            storeLocally(key, file);
        });
    }

    @Override
    public void storeLocally(final BuildCacheKey key, final File file) {
        persistentCache.withFileLock(new Runnable() {
            @Override
            public void run() {
                lock.writeLock().lock();
                try {
                    storeInsideLock(key, file);
                } finally {
                    lock.writeLock().unlock();
                }
            }
        });
    }

    private void storeInsideLock(BuildCacheKey key, File file) {
        LocallyAvailableResource resource = fileStore.move(key.getHashCode(), file);
        fileAccessTracker.markAccessed(resource.getFile());
    }

    @Override
    public void withTempFile(final BuildCacheKey key, final Action<? super File> action) {
        persistentCache.withFileLock(new Runnable() {
            @Override
            public void run() {
                tempFileStore.withTempFile(key, action);
            }
        });
    }

    @Override
    public void close() {
        persistentCache.close();
    }
}
