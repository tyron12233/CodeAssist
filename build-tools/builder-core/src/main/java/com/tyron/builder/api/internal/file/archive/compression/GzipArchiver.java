package com.tyron.builder.api.internal.file.archive.compression;

import com.tyron.builder.api.resources.internal.ReadableResourceInternal;

import com.tyron.builder.internal.IoActions;
import com.tyron.builder.internal.resource.ResourceExceptions;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GzipArchiver extends AbstractArchiver {
    public GzipArchiver(ReadableResourceInternal resource) {
        super(resource);
    }

    @Override
    protected String getSchemePrefix() {
        return "gzip:";
    }

    public static ArchiveOutputStreamFactory getCompressor() {
        // this is not very beautiful but at some point we will
        // get rid of ArchiveOutputStreamFactory in favor of the writable Resource
        return destination -> {
            OutputStream outStr = new FileOutputStream(destination);
            try {
                return new GZIPOutputStream(outStr);
            } catch (Exception e) {
                IoActions.closeQuietly(outStr);
                String message = String.format("Unable to create gzip output stream for file %s.", destination);
                throw new RuntimeException(message, e);
            }
        };
    }

    @Override
    public InputStream read() {
        InputStream input = new BufferedInputStream(resource.read());
        try {
            return new GZIPInputStream(input);
        } catch (Exception e) {
            IoActions.closeQuietly(input);
            throw ResourceExceptions.readFailed(resource.getDisplayName(), e);
        }
    }
}
