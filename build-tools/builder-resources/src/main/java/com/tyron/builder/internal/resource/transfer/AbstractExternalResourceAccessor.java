package com.tyron.builder.internal.resource.transfer;

import com.tyron.builder.api.resources.ResourceException;
import com.tyron.builder.internal.resource.ExternalResource;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ResourceExceptions;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;

public abstract class AbstractExternalResourceAccessor implements ExternalResourceAccessor {
    @Nullable
    @Override
    public <T> T withContent(ExternalResourceName location, boolean revalidate, ExternalResource.ContentAndMetadataAction<T> action) throws ResourceException {
        ExternalResourceReadResponse response = openResource(location, revalidate);
        if (response == null) {
            return null;
        }
        try {
            try {
                try (InputStream inputStream = response.openStream()) {
                    return action.execute(inputStream, response.getMetaData());
                }
            } finally {
                response.close();
            }
        } catch (IOException e) {
            throw ResourceExceptions.getFailed(location.getUri(), e);
        }
    }

    @Nullable
    protected abstract ExternalResourceReadResponse openResource(ExternalResourceName location, boolean revalidate) throws ResourceException;
}
