package org.gradle.caching.internal.packaging;

import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.caching.internal.CacheableEntity;
import org.gradle.caching.internal.origin.OriginMetadata;
import org.gradle.caching.internal.origin.OriginReader;
import org.gradle.caching.internal.origin.OriginWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

public interface BuildCacheEntryPacker {
    PackResult pack(CacheableEntity entity, Map<String, ? extends FileSystemSnapshot> snapshots, OutputStream output, OriginWriter writeOrigin) throws IOException;

    class PackResult {
        private final long entries;

        public PackResult(long entries) {
            this.entries = entries;
        }

        public long getEntries() {
            return entries;
        }
    }

    UnpackResult unpack(CacheableEntity entity, InputStream input, OriginReader readOrigin) throws IOException;

    class UnpackResult {
        private final OriginMetadata originMetadata;
        private final long entries;
        private final Map<String, FileSystemLocationSnapshot> snapshots;

        public UnpackResult(OriginMetadata originMetadata, long entries, Map<String, FileSystemLocationSnapshot> snapshots) {
            this.originMetadata = originMetadata;
            this.entries = entries;
            this.snapshots = snapshots;
        }

        public OriginMetadata getOriginMetadata() {
            return originMetadata;
        }

        public long getEntries() {
            return entries;
        }

        public Map<String, FileSystemLocationSnapshot> getSnapshots() {
            return snapshots;
        }
    }
}