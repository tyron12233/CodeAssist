package com.tyron.builder.internal.resource.transport.http;

import com.tyron.builder.api.resources.ResourceException;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.transfer.ExternalResourceLister;

import java.util.List;

public class HttpResourceLister implements ExternalResourceLister {
    private final HttpResourceAccessor accessor;

    public HttpResourceLister(HttpResourceAccessor accessor) {
        this.accessor = accessor;
    }

    @Override
    public List<String> list(final ExternalResourceName directory) {
        return accessor.withContent(directory, true, (inputStream, metaData) -> {
            String contentType = metaData.getContentType();
            ApacheDirectoryListingParser directoryListingParser = new ApacheDirectoryListingParser();
            try {
                return directoryListingParser.parse(directory.getUri(), inputStream, contentType);
            } catch (Exception e) {
                throw new ResourceException(directory.getUri(), String.format("Unable to parse HTTP directory listing for '%s'.", directory.getUri()), e);
            }
        });
    }
}
