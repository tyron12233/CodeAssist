package com.tyron.builder.internal.file.archive.impl;

import com.google.common.collect.AbstractIterator;
import com.tyron.builder.internal.file.FileException;
import com.tyron.builder.internal.file.archive.ZipEntry;
import com.tyron.builder.internal.file.archive.ZipInput;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;

public class StreamZipInput implements ZipInput {

    private final ZipInputStream in;

    public StreamZipInput(InputStream in) {
        this.in = new ZipInputStream(in);
    }

    @Override
    public Iterator<ZipEntry> iterator() {
        return new AbstractIterator<ZipEntry>() {
            @Override
            protected ZipEntry computeNext() {
                java.util.zip.ZipEntry nextEntry;
                try {
                    nextEntry = in.getNextEntry();
                } catch (IOException e) {
                    throw new FileException(e);
                }
                return nextEntry == null ? endOfData() : new JdkZipEntry(nextEntry, new Supplier<InputStream>() {
                    @Override
                    public InputStream get() {
                        return in;
                    }
                }, new Runnable() {
                    @Override
                    public void run()  {
                        try {
                            in.closeEntry();
                        } catch (IOException e) {
                            throw new FileException(e);
                        }
                    }
                });
            }
        };
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}