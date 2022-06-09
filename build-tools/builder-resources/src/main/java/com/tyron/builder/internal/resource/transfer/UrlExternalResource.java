package com.tyron.builder.internal.resource.transfer;

import com.tyron.builder.api.resources.ResourceException;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.resource.ExternalResource;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ReadableContent;
import com.tyron.builder.internal.resource.ResourceExceptions;
import com.tyron.builder.internal.resource.metadata.DefaultExternalResourceMetaData;
import com.tyron.builder.internal.resource.metadata.ExternalResourceMetaData;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

public class UrlExternalResource extends AbstractExternalResourceAccessor implements ExternalResourceConnector {
    public static ExternalResource open(URL url) throws IOException {
        URI uri;
        try {
            uri = url.toURI();
        } catch (URISyntaxException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
        UrlExternalResource connector = new UrlExternalResource();
        return new AccessorBackedExternalResource(new ExternalResourceName(uri), connector, connector, connector, false);
    }

    @Nullable
    @Override
    public ExternalResourceMetaData getMetaData(ExternalResourceName location, boolean revalidate) throws ResourceException {
        try {
            URL url = location.getUri().toURL();
            URLConnection connection = url.openConnection();
            try {
                return new DefaultExternalResourceMetaData(location.getUri(), connection.getLastModified(), connection.getContentLength(), connection.getContentType(), null, null);
            } finally {
                connection.getInputStream().close();
            }
        } catch (FileNotFoundException e) {
            return null;
        } catch (Exception e) {
            throw ResourceExceptions.getFailed(location.getUri(), e);
        }
    }

    @Nullable
    @Override
    public ExternalResourceReadResponse openResource(final ExternalResourceName location, boolean revalidate) throws ResourceException {
        try {
            URL url = location.getUri().toURL();
            final URLConnection connection = url.openConnection();
            final InputStream inputStream = connection.getInputStream();
            return new ExternalResourceReadResponse() {
                @Override
                public InputStream openStream() {
                    return inputStream;
                }

                @Override
                public ExternalResourceMetaData getMetaData() {
                    return new DefaultExternalResourceMetaData(location.getUri(), connection.getLastModified(), connection.getContentLength(), connection.getContentType(), null, null);
                }

                @Override
                public void close() throws IOException {
                    inputStream.close();
                }
            };
        } catch (FileNotFoundException e) {
            return null;
        } catch (Exception e) {
            throw ResourceExceptions.getFailed(location.getUri(), e);
        }
    }

    @Nullable
    @Override
    public List<String> list(ExternalResourceName parent) throws ResourceException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void upload(ReadableContent resource, ExternalResourceName destination) throws IOException {
        throw new UnsupportedOperationException();
    }
}
