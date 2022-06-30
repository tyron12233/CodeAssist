package com.tyron.builder.internal.file.archive.impl;

import com.google.common.collect.AbstractIterator;
import com.tyron.builder.internal.file.FileException;
import com.tyron.builder.internal.file.archive.ZipEntry;
import com.tyron.builder.internal.file.archive.ZipInput;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

public class FileZipInput implements ZipInput {

    /**
     * Creates a stream of the entries in the given zip file. Caller is responsible for closing the return value.
     *
     * @throws FileException on failure to open the Zip
     */
    public static ZipInput create(File file) throws FileException {
        if (isZipFileSafeToUse()) {
            return new FileZipInput(file);
        } else {
            try {
                return new StreamZipInput(new FileInputStream(file));
            } catch (FileNotFoundException e) {
                throw new FileException(e);
            }
        }
    }

    /**
     * {@link ZipFile} is more efficient, but causes memory leaks on older Java versions, so we only use it on more recent ones.
     */
    private static boolean isZipFileSafeToUse() {
        String versionString = System.getProperty("java.specification.version");
        // Java versions 8 and older had 1.8 versioning scheme, later ones have single number 9, 10, ...
        String[] versionParts = versionString.split("\\.");
        if (versionParts.length < 1) {
            throw new IllegalArgumentException("Could not determine java version from '" + versionString + "'.");
        }
        return Integer.parseInt(versionParts[0]) >= 11;
    }

    private final ZipFile file;
    private final Enumeration<? extends java.util.zip.ZipEntry> entries;

    private FileZipInput(File file) {
        try {
            this.file = new ZipFile(file);
        } catch (IOException e) {
            throw new FileException(e);
        }
        this.entries = this.file.entries();
    }

    @Override
    public Iterator<ZipEntry> iterator() {
        return new AbstractIterator<ZipEntry>() {
            @Override
            protected ZipEntry computeNext() {
                if (!entries.hasMoreElements()) {
                    return endOfData();
                }
                final java.util.zip.ZipEntry zipEntry = entries.nextElement();
                return new JdkZipEntry(zipEntry, new Supplier<InputStream>() {
                    @Override
                    public InputStream get() {
                        try {
                            return file.getInputStream(zipEntry);
                        } catch (IOException e) {
                            throw new FileException(e);
                        }
                    }
                }, null);
            }
        };
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}