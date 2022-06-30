package com.tyron.builder.internal.resource.local;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.Factory;

public abstract class AbstractLocallyAvailableResource implements LocallyAvailableResource {
    private Factory<HashCode> factory;
    // Calculated on demand
    private HashCode sha1;
    private Long contentLength;
    private Long lastModified;

    protected AbstractLocallyAvailableResource(Factory<HashCode> factory) {
        this.factory = factory;
    }

    protected AbstractLocallyAvailableResource(HashCode sha1) {
        this.sha1 = sha1;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public String getDisplayName() {
        return getFile().getPath();
    }

    @Override
    public HashCode getSha1() {
        if (sha1 == null) {
            sha1 = factory.create();
        }
        return sha1;
    }

    @Override
    public long getContentLength() {
        if (contentLength == null) {
            contentLength = getFile().length();
        }
        return contentLength;
    }

    @Override
    public long getLastModified() {
        if (lastModified == null) {
            lastModified = getFile().lastModified();
        }
        return lastModified;
    }

}