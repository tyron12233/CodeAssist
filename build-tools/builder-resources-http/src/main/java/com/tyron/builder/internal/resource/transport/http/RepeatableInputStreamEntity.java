package com.tyron.builder.internal.resource.transport.http;

import org.apache.commons.io.IOUtils;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.ContentType;
import com.tyron.builder.internal.IoActions;
import com.tyron.builder.internal.resource.ReadableContent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class RepeatableInputStreamEntity extends AbstractHttpEntity {
    private final ReadableContent source;

    public RepeatableInputStreamEntity(ReadableContent source, ContentType contentType) {
        super();
        this.source = source;
        if (contentType != null) {
            setContentType(contentType.toString());
        }
    }

    @Override
    public boolean isRepeatable() {
        return true;
    }

    @Override
    public long getContentLength() {
        return source.getContentLength();
    }

    @Override
    public InputStream getContent() throws IOException, IllegalStateException {
        return source.open();
    }

    @Override
    public void writeTo(OutputStream outstream) throws IOException {
        InputStream content = getContent();
        try {
            IOUtils.copyLarge(content, outstream);
        } finally {
            IoActions.closeQuietly(content);
        }
    }

    @Override
    public boolean isStreaming() {
        return true;
    }
}
