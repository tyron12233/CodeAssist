package com.tyron.builder.internal.resource.metadata;

import com.google.common.hash.HashCode;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.Date;

public interface ExternalResourceMetaData {

    URI getLocation();

    @Nullable
    Date getLastModified();

    @Nullable
    String getContentType();

    /**
     * Returns -1 when the content length is unknown.
     */
    long getContentLength();

    /**
     * Some kind of opaque checksum that was advertised by the remote “server”.
     *
     * For HTTP this is likely the value of the ETag header but it may be any kind of opaque checksum.
     *
     * @return The entity tag, or null if there was no advertised or suitable etag.
     */
    @Nullable
    String getEtag();

    /**
     * The advertised sha-1 of the external resource.
     *
     * This should only be collected if it is very cheap to do so. For example, some HTTP servers send an
     * “X-Checksum-Sha1” that makes the sha1 available cheaply. In this case it makes sense to advertise this as metadata here.
     *
     * @return The sha1, or null if it's unknown.
     */
    @Nullable
    HashCode getSha1();

}
