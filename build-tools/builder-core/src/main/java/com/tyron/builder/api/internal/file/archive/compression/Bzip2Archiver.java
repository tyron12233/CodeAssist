package com.tyron.builder.api.internal.file.archive.compression;

import org.apache.tools.bzip2.CBZip2InputStream;
import org.apache.tools.bzip2.CBZip2OutputStream;
import com.tyron.builder.api.resources.internal.ReadableResourceInternal;
import com.tyron.builder.internal.IoActions;
import com.tyron.builder.internal.resource.ResourceExceptions;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class Bzip2Archiver extends AbstractArchiver {
    public Bzip2Archiver(ReadableResourceInternal resource) {
        super(resource);
    }

    @Override
    protected String getSchemePrefix() {
        return "bzip2:";
    }

    public static ArchiveOutputStreamFactory getCompressor() {
        // this is not very beautiful but at some point we will
        // get rid of ArchiveOutputStreamFactory in favor of the writable Resource
        return destination -> {
            OutputStream outStr = new BufferedOutputStream(new FileOutputStream(destination));
            try {
                outStr.write('B');
                outStr.write('Z');
                return new CBZip2OutputStream(outStr);
            } catch (Exception e) {
                IoActions.closeQuietly(outStr);
                String message = String.format("Unable to create bzip2 output stream for file %s", destination);
                throw new RuntimeException(message, e);
            }
        };
    }

    @Override
    public InputStream read() {
        InputStream input = new BufferedInputStream(resource.read());
        try {
            // CBZip2InputStream expects the opening "BZ" to be skipped
            byte[] skip = new byte[2];
            input.read(skip);
            return new CBZip2InputStream(input);
        } catch (Exception e) {
            IoActions.closeQuietly(input);
            throw ResourceExceptions.readFailed(resource.getDisplayName(), e);
        }
    }
}
