package com.tyron.builder.internal.classpath;

import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.internal.file.FileAccessTimeJournal;
import com.tyron.builder.internal.file.FileAccessTracker;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.UserHome.class)
public interface ClasspathTransformerCacheFactory {
    PersistentCache createCache(GlobalScopedCache cacheRepository, FileAccessTimeJournal fileAccessTimeJournal);

    FileAccessTracker createFileAccessTracker(PersistentCache persistentCache, FileAccessTimeJournal fileAccessTimeJournal);
}
