package com.tyron.builder.api.internal.file.copy;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.file.ContentFilterable;
import com.tyron.builder.api.file.DuplicatesStrategy;
import com.tyron.builder.api.file.ExpandDetails;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.internal.file.AbstractFileTreeElement;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.internal.file.Chmod;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

public class DefaultFileCopyDetails extends AbstractFileTreeElement implements FileVisitDetails, FileCopyDetailsInternal {
    private final FileVisitDetails fileDetails;
    private final CopySpecResolver specResolver;
    private final FilterChain filterChain;
    private final ObjectFactory objectFactory;
    private boolean defaultDuplicatesStrategy;
    private RelativePath relativePath;
    private boolean excluded;
    private Integer mode;
    private DuplicatesStrategy duplicatesStrategy;

    @Inject
    public DefaultFileCopyDetails(FileVisitDetails fileDetails, CopySpecResolver specResolver, ObjectFactory objectFactory, Chmod chmod) {
        super(chmod);
        this.filterChain = new FilterChain(specResolver.getFilteringCharset());
        this.fileDetails = fileDetails;
        this.specResolver = specResolver;
        this.objectFactory = objectFactory;
        this.duplicatesStrategy = specResolver.getDuplicatesStrategy();
        this.defaultDuplicatesStrategy = specResolver.isDefaultDuplicateStrategy();
    }

    @Override
    public boolean isIncludeEmptyDirs() {
        return specResolver.getIncludeEmptyDirs();
    }

    @Override
    public String getDisplayName() {
        return fileDetails.toString();
    }

    @Override
    public void stopVisiting() {
        fileDetails.stopVisiting();
    }

    @Override
    public File getFile() {
        if (filterChain.hasFilters()) {
            throw new UnsupportedOperationException();
        } else {
            return fileDetails.getFile();
        }
    }

    @Override
    public boolean isDirectory() {
        return fileDetails.isDirectory();
    }

    @Override
    public long getLastModified() {
        return fileDetails.getLastModified();
    }

    @Override
    public long getSize() {
        if (filterChain.hasFilters()) {
            ByteCountingOutputStream outputStream = new ByteCountingOutputStream();
            copyTo(outputStream);
            return outputStream.size;
        } else {
            return fileDetails.getSize();
        }
    }

    @Override
    public InputStream open() {
        if (filterChain.hasFilters()) {
            return filterChain.transform(fileDetails.open());
        } else {
            return fileDetails.open();
        }
    }

    @Override
    public void copyTo(OutputStream output) {
        if (filterChain.hasFilters()) {
            super.copyTo(output);
        } else {
            fileDetails.copyTo(output);
        }
    }

    @Override
    public boolean copyTo(File target) {
        if (filterChain.hasFilters()) {
            return super.copyTo(target);
        } else {
            final boolean copied = fileDetails.copyTo(target);
            adaptPermissions(target);
            return copied;
        }
    }

    private void adaptPermissions(File target) {
        int specMode = getMode();
        getChmod().chmod(target, specMode);
    }

    @Override
    public RelativePath getRelativePath() {
        if (relativePath == null) {
            RelativePath path = fileDetails.getRelativePath();
            relativePath = specResolver.getDestPath().append(path.isFile(), path.getSegments());
        }
        return relativePath;
    }

    @Override
    public int getMode() {
        if (mode != null) {
            return mode;
        }

        Integer specMode = getSpecMode();
        if (specMode != null) {
            return specMode;
        }

        return fileDetails.getMode();
    }

    @Nullable
    private Integer getSpecMode() {
        return fileDetails.isDirectory() ? specResolver.getDirMode() : specResolver.getFileMode();
    }

    @Override
    public void setRelativePath(RelativePath path) {
        this.relativePath = path;
    }

    @Override
    public void setName(String name) {
        relativePath = getRelativePath().replaceLastName(name);
    }

    @Override
    public void setPath(String path) {
        relativePath = RelativePath.parse(getRelativePath().isFile(), path);
    }

    boolean isExcluded() {
        return excluded;
    }

    @Override
    public void exclude() {
        excluded = true;
    }

    @Override
    public void setMode(int mode) {
        this.mode = mode;
    }
//
//    @Override
//    public ContentFilterable filter(Closure closure) {
//        return filter(new ClosureBackedTransformer(closure));
//    }

    @Override
    public ContentFilterable filter(Transformer<String, String> transformer) {
        filterChain.add(transformer);
        return this;
    }

    @Override
    public ContentFilterable filter(Map<String, ?> properties, Class<? extends FilterReader> filterType) {
        filterChain.add(filterType, properties);
        return this;
    }

    @Override
    public ContentFilterable filter(Class<? extends FilterReader> filterType) {
        filterChain.add(filterType);
        return this;
    }

    @Override
    public ContentFilterable expand(Map<String, ?> properties) {
        return expand(properties, Actions.doNothing());
    }

    @Override
    public ContentFilterable expand(Map<String, ?> properties, Action<? super ExpandDetails> action) {
        ExpandDetails details = objectFactory.newInstance(ExpandDetails.class);
        details.getEscapeBackslash().convention(false);
        action.execute(details);
        filterChain.expand(properties, details.getEscapeBackslash());
        return this;
    }

    @Override
    public void setDuplicatesStrategy(DuplicatesStrategy strategy) {
        this.duplicatesStrategy = strategy;
        this.defaultDuplicatesStrategy = strategy == DuplicatesStrategy.INHERIT;
    }

    @Override
    public DuplicatesStrategy getDuplicatesStrategy() {
        return this.duplicatesStrategy;
    }

    public boolean isDefaultDuplicatesStrategy() {
        return defaultDuplicatesStrategy;
    }

    @Override
    public String getSourceName() {
        return this.fileDetails.getName();
    }

    @Override
    public String getSourcePath() {
        return this.fileDetails.getPath();
    }

    @Override
    public RelativePath getRelativeSourcePath() {
        return this.fileDetails.getRelativePath();
    }

    private static class ByteCountingOutputStream extends OutputStream {
        long size;

        @Override
        public void write(int b) throws IOException {
            size++;
        }

        @Override
        public void write(byte[] b) throws IOException {
            size += b.length;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            size += len;
        }
    }
}
