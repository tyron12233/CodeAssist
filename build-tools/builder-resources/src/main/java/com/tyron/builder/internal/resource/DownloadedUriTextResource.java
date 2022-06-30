package com.tyron.builder.internal.resource;

import com.tyron.builder.internal.file.RelativeFilePathResolver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;

/**
 * A {@link TextResource} implementation backed by a {@link UriTextResource}. This helps hide the internal details about file caching.
 */
public class DownloadedUriTextResource extends UriTextResource {

    private final String contentType;
    private final File downloadedResource;

    public DownloadedUriTextResource(String description, URI sourceUri, String contentType, File downloadedResource, RelativeFilePathResolver resolver) {
        super(description, sourceUri, resolver);
        this.contentType = contentType;
        this.downloadedResource = downloadedResource;
    }

    @Override
    protected Reader openReader() throws IOException {
        Charset charset = extractCharacterEncoding(contentType, DEFAULT_ENCODING);
        InputStream inputStream = new FileInputStream(downloadedResource);
        return new InputStreamReader(inputStream, charset);
    }

    @Override
    public Charset getCharset() {
        return extractCharacterEncoding(contentType, DEFAULT_ENCODING);
    }
}
