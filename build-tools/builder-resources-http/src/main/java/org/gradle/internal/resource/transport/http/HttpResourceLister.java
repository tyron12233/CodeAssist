package org.gradle.internal.resource.transport.http;

import org.gradle.api.resources.ResourceException;
import org.gradle.internal.resource.ExternalResourceName;
import org.gradle.internal.resource.transfer.ExternalResourceLister;

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
