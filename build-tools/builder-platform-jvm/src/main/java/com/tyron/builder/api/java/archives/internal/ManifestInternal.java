package com.tyron.builder.api.java.archives.internal;

import com.tyron.builder.api.java.archives.Manifest;

import java.io.OutputStream;

/**
 * Internal protocol for Manifest.
 *
 * @since 2.14
 */
public interface ManifestInternal extends Manifest {

    /**
     * See {@link com.tyron.builder.jvm.tasks.Jar#getManifestContentCharset()}
     */
    String getContentCharset();

    /**
     * See {@link com.tyron.builder.jvm.tasks.Jar#getManifestContentCharset()}
     */
    void setContentCharset(String contentCharset);

    /**
     * Writes the manifest into a stream.
     *
     * The manifest will be encoded using the character set defined by the {@link #getContentCharset()} property.
     *
     * @param outputStream The stream to write the manifest to
     * @return this
     */
    Manifest writeTo(OutputStream outputStream);

}
