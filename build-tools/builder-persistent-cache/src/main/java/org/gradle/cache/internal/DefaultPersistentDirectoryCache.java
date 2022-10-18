package org.gradle.cache.internal;

import org.gradle.api.Action;
import org.gradle.api.UncheckedIOException;
import org.gradle.util.GUtil;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.util.internal.GFileUtils;
import org.gradle.cache.CacheBuilder;
import org.gradle.cache.CleanupAction;
import org.gradle.cache.FileLock;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.LockOptions;
import org.gradle.cache.PersistentCache;

import java.io.File;

import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

public class DefaultPersistentDirectoryCache extends DefaultPersistentDirectoryStore implements ReferencablePersistentCache {

    private static final Logger LOGGER = Logger .getLogger(DefaultPersistentDirectoryCache.class.getSimpleName());

    private final Properties properties = new Properties();
    private final Action<? super PersistentCache> initAction;

    public DefaultPersistentDirectoryCache(File dir, String displayName, Map<String, ?> properties, CacheBuilder.LockTarget lockTarget, LockOptions lockOptions, Action<? super PersistentCache> initAction, CleanupAction cleanupAction, FileLockManager lockManager, ExecutorFactory executorFactory, ProgressLoggerFactory progressLoggerFactory) {
        super(dir, displayName, lockTarget, lockOptions, cleanupAction, lockManager, executorFactory, progressLoggerFactory);
        this.initAction = initAction;
        this.properties.putAll(properties);
    }

    @Override
    protected CacheInitializationAction getInitAction() {
        return new Initializer();
    }

    public Properties getProperties() {
        return properties;
    }

    private class Initializer implements CacheInitializationAction {
        @Override
        public boolean requiresInitialization(FileLock lock) {
            if (!lock.getUnlockedCleanly()) {
                if (lock.getState().canDetectChanges() && !lock.getState().isInInitialState()) {
                    LOGGER.warning("Invalidating " + DefaultPersistentDirectoryCache.this + " as it was not closed cleanly.");
                }
                return true;
            }

            if (!properties.isEmpty()) {
                if (!propertiesFile.exists()) {
                    LOGGER.info("Invalidating " + DefaultPersistentDirectoryCache.this + " as cache properties file " + propertiesFile.getAbsolutePath() + " is missing and cache properties are not empty.");
                    return true;
                }
                Properties cachedProperties = GUtil.loadProperties(propertiesFile);
                for (Map.Entry<?, ?> entry : properties.entrySet()) {
                    String previousValue = cachedProperties.getProperty(entry.getKey().toString());
                    String currentValue = entry.getValue().toString();
                    if (!currentValue.equals(previousValue)) {
                        LOGGER.info("Invalidating " + DefaultPersistentDirectoryCache.this + " as cache property " + entry.getKey() + " has changed from " + previousValue + " to " + currentValue);
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void initialize(FileLock fileLock) {
            File[] files = getBaseDir().listFiles();
            if (files == null) {
                throw new UncheckedIOException("Cannot list files in " + getBaseDir());
            }
            for (File file : files) {
                if (fileLock.isLockFile(file) || file.equals(propertiesFile)) {
                    continue;
                }
                GFileUtils.forceDelete(file);
            }
            if (initAction != null) {
                initAction.execute(DefaultPersistentDirectoryCache.this);
            }
            GUtil.saveProperties(properties, propertiesFile);
        }
    }
}