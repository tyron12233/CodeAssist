package org.gradle.internal.resource.local;

import org.gradle.internal.resource.ReadableContent;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class ByteArrayReadableContent implements ReadableContent {
    private final byte[] source;

    public ByteArrayReadableContent(byte[] source) {
        this.source = source;
    }

    @Override
    public long getContentLength() {
        return source.length;
    }

    @Override
    public InputStream open() {
        return new ByteArrayInputStream(source);
    }
}
