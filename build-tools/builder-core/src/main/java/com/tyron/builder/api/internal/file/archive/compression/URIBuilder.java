package com.tyron.builder.api.internal.file.archive.compression;

import com.tyron.builder.util.internal.GUtil;

import java.net.URI;

public class URIBuilder {
    private final URI uri;
    private String schemePrefix = "";

    public URIBuilder(URI uri) {
        this.uri = uri;
    }

    public URIBuilder schemePrefix(String schemePrefix) {
        assert GUtil.isTrue(schemePrefix);
        this.schemePrefix = schemePrefix;
        return this;
    }

    public URI build() {
        assert GUtil.isTrue(schemePrefix);
        try {
            return new URI(schemePrefix + ":" + uri.toString());
        } catch (Exception e) {
            throw new RuntimeException("Unable to build URI based on supplied URI: " + uri, e);
        }
    }
}
