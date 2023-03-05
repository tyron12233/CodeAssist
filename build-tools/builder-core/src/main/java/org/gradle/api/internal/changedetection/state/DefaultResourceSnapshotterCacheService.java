package org.gradle.api.internal.changedetection.state;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import org.gradle.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import org.gradle.internal.fingerprint.hashing.RegularFileSnapshotContext;
import org.gradle.internal.fingerprint.hashing.RegularFileSnapshotContextHasher;
import org.gradle.internal.hash.Hashes;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.cache.PersistentIndexedCache;
import org.gradle.internal.io.IoSupplier;

import javax.annotation.Nullable;
import java.io.IOException;

public class DefaultResourceSnapshotterCacheService implements ResourceSnapshotterCacheService {
    private static final HashCode NO_HASH = Hashes.signature(CachingResourceHasher.class.getName() + " : no hash");
    private final PersistentIndexedCache<HashCode, HashCode> persistentCache;

    public DefaultResourceSnapshotterCacheService(PersistentIndexedCache<HashCode, HashCode> persistentCache) {
        this.persistentCache = persistentCache;
    }

    @Nullable
    @Override
    public HashCode hashFile(FileSystemLocationSnapshot snapshot, FileSystemLocationSnapshotHasher hasher, HashCode configurationHash) throws IOException {
        return hashFile(snapshot, () -> hasher.hash(snapshot), configurationHash);
    }

    @Nullable
    @Override
    public HashCode hashFile(RegularFileSnapshotContext fileSnapshotContext, RegularFileSnapshotContextHasher hasher, HashCode configurationHash) throws IOException {
        return hashFile(fileSnapshotContext.getSnapshot(), () -> hasher.hash(fileSnapshotContext), configurationHash);
    }

    @Nullable
    private HashCode hashFile(FileSystemLocationSnapshot snapshot, IoSupplier<HashCode> hashCodeSupplier, HashCode configurationHash) throws IOException {
        HashCode resourceHashCacheKey = resourceHashCacheKey(snapshot.getHash(), configurationHash);

        HashCode resourceHash = persistentCache.getIfPresent(resourceHashCacheKey);
        if (resourceHash != null) {
            if (resourceHash.equals(NO_HASH)) {
                return null;
            }
            return resourceHash;
        }

        resourceHash = hashCodeSupplier.get();

        if (resourceHash != null) {
            persistentCache.put(resourceHashCacheKey, resourceHash);
        } else {
            persistentCache.put(resourceHashCacheKey, NO_HASH);
        }
        return resourceHash;
    }

    private static HashCode resourceHashCacheKey(HashCode contentHash, HashCode configurationHash) {
        Hasher hasher = Hashes.newHasher();
        Hashes.putHash(hasher, configurationHash);
        Hashes.putHash(hasher, contentHash);
        return hasher.hash();
    }
}