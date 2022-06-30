package com.tyron.builder.api.internal.resources;

import com.google.common.io.Files;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.tasks.TaskDependencyInternal;
import com.tyron.builder.api.resources.ResourceException;
import com.tyron.builder.api.resources.internal.TextResourceInternal;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.internal.resource.ResourceExceptions;
import com.tyron.builder.internal.resource.TextResource;
import com.tyron.builder.internal.resource.TextUriResourceLoader;
import com.tyron.builder.internal.verifier.HttpRedirectVerifier;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;

/**
 * A {@link com.tyron.builder.api.resources.TextResource} that adapts a {@link TextResource}.
 */
public class ApiTextResourceAdapter implements TextResourceInternal {
    private final URI uri;
    private final TextUriResourceLoader textUriResourceLoader;
    private final TemporaryFileProvider tempFileProvider;
    private TextResource textResource;

    public ApiTextResourceAdapter(TextUriResourceLoader textUriResourceLoader, TemporaryFileProvider tempFileProvider, URI uri) {
        this.uri = uri;
        this.textUriResourceLoader = textUriResourceLoader;
        this.tempFileProvider = tempFileProvider;
    }

    @Override
    public String asString() {
        return getWrappedTextResource().getText();
    }

    @Override
    public Reader asReader() {
        return getWrappedTextResource().getAsReader();
    }

    @Override
    public File asFile(String targetCharset) {
        try {
            File file = getWrappedTextResource().getFile();
            if (file == null) {
                file = tempFileProvider.createTemporaryFile("wrappedInternalText", ".txt", "resource");
                Files.asCharSink(file, Charset.forName(targetCharset)).write(getWrappedTextResource().getText());
                return file;
            }
            Charset sourceCharset = getWrappedTextResource().getCharset();
            Charset targetCharsetObj = Charset.forName(targetCharset);
            if (targetCharsetObj.equals(sourceCharset)) {
                return file;
            }

            File targetFile = tempFileProvider.createTemporaryFile("uriTextResource", ".txt", "resource");
            try {
                Files.asCharSource(file, sourceCharset).copyTo(Files.asCharSink(targetFile, targetCharsetObj));
                return targetFile;
            } catch (IOException e) {
                throw new ResourceException("Could not write " + getDisplayName() + " content to " + targetFile + ".", e);
            }
        } catch (Exception e) {
            throw ResourceExceptions.readFailed(getDisplayName(), e);
        }
    }

    @Override
    public File asFile() {
        return asFile(Charset.defaultCharset().name());
    }

    @Override
    public Object getInputProperties() {
        return uri;
    }

    @Override
    public FileCollection getInputFiles() {
        return null;
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return TaskDependencyInternal.EMPTY;
    }

    @Override
    public String getDisplayName() {
        return getWrappedTextResource().getDisplayName();
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    private TextResource getWrappedTextResource() {
        if (textResource == null) {
            textResource = textUriResourceLoader.loadUri("textResource", uri);
        }
        return textResource;
    }

    public static class Factory {
        private final TextUriResourceLoader.Factory textUriResourceLoaderFactory;
        private final TemporaryFileProvider tempFileProvider;

        @Inject
        public Factory(TextUriResourceLoader.Factory textUriResourceLoaderFactory, @Nullable TemporaryFileProvider tempFileProvider) {
            this.textUriResourceLoaderFactory = textUriResourceLoaderFactory;
            this.tempFileProvider = tempFileProvider;
        }

        ApiTextResourceAdapter create(URI uri, HttpRedirectVerifier httpRedirectVerifier) {
            TextUriResourceLoader uriResourceLoader = textUriResourceLoaderFactory.create(httpRedirectVerifier);
            return new ApiTextResourceAdapter(uriResourceLoader, tempFileProvider, uri);
        }
    }
}
