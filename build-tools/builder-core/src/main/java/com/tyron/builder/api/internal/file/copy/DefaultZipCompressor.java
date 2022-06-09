package com.tyron.builder.api.internal.file.copy;

import org.apache.tools.zip.Zip64Mode;
import org.apache.tools.zip.ZipOutputStream;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.internal.IoActions;

import java.io.File;
import java.io.IOException;

public class DefaultZipCompressor implements ZipCompressor {
    private final int entryCompressionMethod;
    private final Zip64Mode zip64Mode;

    public DefaultZipCompressor(boolean allowZip64Mode, int entryCompressionMethod) {
        this.entryCompressionMethod = entryCompressionMethod;
        zip64Mode = allowZip64Mode ? Zip64Mode.AsNeeded : Zip64Mode.Never;
    }

    @Override
    public ZipOutputStream createArchiveOutputStream(File destination) throws IOException {
        ZipOutputStream outStream = new ZipOutputStream(destination);
        try {
            outStream.setUseZip64(zip64Mode);
            outStream.setMethod(entryCompressionMethod);
            return outStream;
        } catch (Exception e) {
            IoActions.closeQuietly(outStream);
            String message = String.format("Unable to create ZIP output stream for file %s.", destination);
            throw new UncheckedIOException(message, e);
        }
    }

}
