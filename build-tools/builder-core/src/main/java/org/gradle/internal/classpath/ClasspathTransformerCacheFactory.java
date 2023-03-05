package org.gradle.internal.classpath;

import org.gradle.cache.PersistentCache;
import org.gradle.cache.scopes.GlobalScopedCache;
import org.gradle.internal.file.FileAccessTimeJournal;
import org.gradle.internal.file.FileAccessTracker;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.UserHome.class)
public interface ClasspathTransformerCacheFactory {
    PersistentCache createCache(GlobalScopedCache cacheRepository, FileAccessTimeJournal fileAccessTimeJournal);

    FileAccessTracker createFileAccessTracker(PersistentCache persistentCache, FileAccessTimeJournal fileAccessTimeJournal);
}
