package com.tyron.builder.api.internal.changedetection.state;

import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;
import static com.tyron.builder.internal.serialize.BaseSerializerFactory.FILE_SERIALIZER;
import static com.tyron.builder.internal.serialize.BaseSerializerFactory.LONG_SERIALIZER;

import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.PersistentIndexedCache;
import com.tyron.builder.cache.PersistentIndexedCacheParameters;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.file.FileAccessTimeJournal;
import com.tyron.builder.util.GUtil;

import java.io.File;
import java.util.Properties;

public class DefaultFileAccessTimeJournal implements FileAccessTimeJournal, Stoppable {

    public static final String CACHE_KEY = "journal-1";
    public static final String FILE_ACCESS_CACHE_NAME = "file-access";
    public static final String FILE_ACCESS_PROPERTIES_FILE_NAME = FILE_ACCESS_CACHE_NAME + ".properties";
    public static final String INCEPTION_TIMESTAMP_KEY = "inceptionTimestamp";

    private final PersistentCache cache;
    private final PersistentIndexedCache<File, Long> store;
    private final long inceptionTimestamp;

    public DefaultFileAccessTimeJournal(GlobalScopedCache cacheRepository, InMemoryCacheDecoratorFactory cacheDecoratorFactory) {
        cache = cacheRepository
            .crossVersionCache(CACHE_KEY)
            .withCrossVersionCache(CacheBuilder.LockTarget.CacheDirectory)
            .withDisplayName("journal cache")
            .withLockOptions(mode(FileLockManager.LockMode.OnDemand)) // lock on demand
            .open();
        store = cache.createCache(PersistentIndexedCacheParameters
                .of(FILE_ACCESS_CACHE_NAME, FILE_SERIALIZER, LONG_SERIALIZER)
            .withCacheDecorator(cacheDecoratorFactory.decorator(10000, true)));
        inceptionTimestamp = loadOrPersistInceptionTimestamp();
    }

    private long loadOrPersistInceptionTimestamp() {
        return cache.useCache(() -> {
            File propertiesFile = new File(cache.getBaseDir(), FILE_ACCESS_PROPERTIES_FILE_NAME);
            if (propertiesFile.exists()) {
                Properties properties = GUtil.loadProperties(propertiesFile);
                String inceptionTimestamp = properties.getProperty(INCEPTION_TIMESTAMP_KEY);
                if (inceptionTimestamp != null) {
                    return Long.parseLong(inceptionTimestamp);
                }
            }
            long inceptionTimestamp = System.currentTimeMillis();
            Properties properties = new Properties();
            properties.setProperty(INCEPTION_TIMESTAMP_KEY, String.valueOf(inceptionTimestamp));
            GUtil.saveProperties(properties, propertiesFile);
            return inceptionTimestamp;
        });
    }

    @Override
    public void stop() {
        cache.close();
    }

    @Override
    public void setLastAccessTime(File file, long millis) {
        store.put(file, millis);
    }

    @Override
    public long getLastAccessTime(File file) {
        Long value = store.getIfPresent(file);
        if (value == null) {
            return Math.max(inceptionTimestamp, file.lastModified());
        }
        return value;
    }

    @Override
    public void deleteLastAccessTime(File file) {
        store.remove(file);
    }
}
