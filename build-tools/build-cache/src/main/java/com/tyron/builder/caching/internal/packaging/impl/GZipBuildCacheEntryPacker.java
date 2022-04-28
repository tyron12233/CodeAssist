package com.tyron.builder.caching.internal.packaging.impl;

import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.caching.internal.CacheableEntity;
import com.tyron.builder.caching.internal.origin.OriginReader;
import com.tyron.builder.caching.internal.origin.OriginWriter;
import com.tyron.builder.caching.internal.packaging.BuildCacheEntryPacker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GZipBuildCacheEntryPacker implements BuildCacheEntryPacker {
    private final BuildCacheEntryPacker delegate;

    public GZipBuildCacheEntryPacker(BuildCacheEntryPacker delegate) {
        this.delegate = delegate;
    }

    @Override
    public PackResult pack(CacheableEntity entity, Map<String, ? extends FileSystemSnapshot> snapshots, OutputStream output, OriginWriter writeOrigin) throws IOException {
        try (GZIPOutputStream gzipOutput = new GZIPOutputStream(output)) {
            return delegate.pack(entity, snapshots, gzipOutput, writeOrigin);
        }
    }

    @Override
    public UnpackResult unpack(CacheableEntity entity, InputStream input, OriginReader readOrigin) throws IOException {
        try (GZIPInputStream gzipInput = new GZIPInputStream(input)) {
            return delegate.unpack(entity, gzipInput, readOrigin);
        }
    }
}