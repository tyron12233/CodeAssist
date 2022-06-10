package org.gradle.api.internal.file.copy;

import org.apache.tools.zip.ZipOutputStream;
import org.gradle.api.internal.file.archive.compression.ArchiveOutputStreamFactory;

import java.io.File;
import java.io.IOException;

public interface ZipCompressor extends ArchiveOutputStreamFactory {

    @Override
    ZipOutputStream createArchiveOutputStream(File destination) throws IOException;

}
