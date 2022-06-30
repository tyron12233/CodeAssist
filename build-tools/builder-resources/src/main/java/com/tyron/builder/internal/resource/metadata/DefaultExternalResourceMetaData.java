package com.tyron.builder.internal.resource.metadata;

import com.google.common.hash.HashCode;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.Date;

public class DefaultExternalResourceMetaData implements ExternalResourceMetaData {
    private final URI location;
    private final Date lastModified;
    private final long contentLength;
    private final String etag;
    private final HashCode sha1;
    private final String contentType;

    public DefaultExternalResourceMetaData(URI location, long lastModified, long contentLength) {
        this(location, lastModified > 0 ? new Date(lastModified) : null, contentLength, null, null, null);
    }

    public DefaultExternalResourceMetaData(URI location, long lastModified, long contentLength, @Nullable String contentType, @Nullable String etag, @Nullable HashCode sha1) {
        this(location, lastModified > 0 ? new Date(lastModified) : null, contentLength, contentType, etag, sha1);
    }

    public DefaultExternalResourceMetaData(URI location, @Nullable Date lastModified, long contentLength, @Nullable String contentType, @Nullable String etag, @Nullable HashCode sha1) {
        this.location = location;
        this.lastModified = lastModified;
        this.contentLength = contentLength;
        this.contentType = contentType;
        this.etag = etag;
        this.sha1 = sha1;
    }

    @Override
    public URI getLocation() {
        return location;
    }

    @Nullable
    @Override
    public Date getLastModified() {
        return lastModified;
    }

    @Override
    public long getContentLength() {
        return contentLength;
    }

    @Nullable
    @Override
    public String getContentType() {
        return contentType;
    }

    @Nullable
    @Override
    public String getEtag() {
        return etag;
    }

    @Nullable
    @Override
    public HashCode getSha1() {
        return sha1;
    }
}
