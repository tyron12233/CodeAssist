/*
 * Copyright (c) 2005, 2011, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package com.sun.tools.javac.file;

import com.sun.tools.javac.file.JavacFileManager.Archive;
import com.sun.tools.javac.file.RelativePath.RelativeDirectory;
import com.sun.tools.javac.file.RelativePath.RelativeFile;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.List;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.util.Set;

import javax.tools.JavaFileObject;

/**
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own risk.
 * This code and its internal interfaces are subject to change or
 * deletion without notice.</b>
 */
public class ZipFileIndexArchive implements Archive {

    private final ZipFileIndex zfIndex;
    private JavacFileManager fileManager;

    public ZipFileIndexArchive(JavacFileManager fileManager, ZipFileIndex zdir) throws IOException {
        super();
        this.fileManager = fileManager;
        this.zfIndex = zdir;
    }

    public boolean contains(RelativePath name) {
        return zfIndex.contains(name);
    }

    public List<String> getFiles(RelativeDirectory subdirectory) {
        return zfIndex.getFiles(subdirectory);
    }

    public JavaFileObject getFileObject(RelativeDirectory subdirectory, String file) {
        RelativeFile fullZipFileName = new RelativeFile(subdirectory, file);
        ZipFileIndex.Entry entry = zfIndex.getZipIndexEntry(fullZipFileName);
        JavaFileObject ret = new ZipFileIndexFileObject(fileManager, zfIndex, entry, zfIndex.getZipFile());
        return ret;
    }

    public Set<RelativeDirectory> getSubdirectories() {
        return zfIndex.getAllDirectories();
    }

    public void close() throws IOException {
        zfIndex.close();
    }

    @Override
    public String toString() {
        return "ZipFileIndexArchive[" + zfIndex + "]";
    }

    /**
     * A subclass of JavaFileObject representing zip entries using the com.sun.tools.javac.file.ZipFileIndex implementation.
     */
    public static class ZipFileIndexFileObject extends BaseFileObject {

        /** The entry's name.
         */
        private String name;

        /** The zipfile containing the entry.
         */
        ZipFileIndex zfIndex;

        /** The underlying zip entry object.
         */
        ZipFileIndex.Entry entry;

        /** The InputStream for this zip entry (file.)
         */
        InputStream inputStream = null;

        /** The name of the zip file where this entry resides.
         */
        File zipName;


        ZipFileIndexFileObject(JavacFileManager fileManager, ZipFileIndex zfIndex, ZipFileIndex.Entry entry, File zipFileName) {
            super(fileManager);
            this.name = entry.getFileName();
            this.zfIndex = zfIndex;
            this.entry = entry;
            this.zipName = zipFileName;
        }

        @Override
        public URI toUri() {
            return createJarUri(zipName, getPrefixedEntryName());
        }

        @Override
        public String getName() {
            return zipName + "(" + getPrefixedEntryName() + ")";
        }

        @Override
        public String getShortName() {
            return zipName.getName() + "(" + entry.getName() + ")";
        }

        @Override
        public Kind getKind() {
            return getKind(entry.getName());
        }

        @Override
        public InputStream openInputStream() throws IOException {
            if (inputStream == null) {
                Assert.checkNonNull(entry); // see constructor
                inputStream = new ByteArrayInputStream(zfIndex.read(entry));
            }
            return inputStream;
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public CharBuffer getCharContent(boolean ignoreEncodingErrors) throws IOException {
            CharBuffer cb = fileManager.getCachedContent(this);
            if (cb == null) {
                InputStream in = new ByteArrayInputStream(zfIndex.read(entry));
                try {
                    ByteBuffer bb = fileManager.makeByteBuffer(in);
                    JavaFileObject prev = fileManager.log.useSource(this);
                    try {
                        cb = fileManager.decode(bb, ignoreEncodingErrors);
                    } finally {
                        fileManager.log.useSource(prev);
                    }
                    fileManager.recycleByteBuffer(bb); // save for next time
                    if (!ignoreEncodingErrors)
                        fileManager.cache(this, cb);
                } finally {
                    in.close();
                }
            }
            return cb;
        }

        @Override
        public Writer openWriter() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLastModified() {
            return entry.getLastModified();
        }

        @Override
        public boolean delete() {
            throw new UnsupportedOperationException();
        }

        @Override
        protected CharsetDecoder getDecoder(boolean ignoreEncodingErrors) {
            return fileManager.getDecoder(fileManager.getEncodingName(), ignoreEncodingErrors);
        }

        @Override
        protected String inferBinaryName(Iterable<? extends File> path) {
            String entryName = entry.getName();
            if (zfIndex.symbolFilePrefix != null) {
                String prefix = zfIndex.symbolFilePrefix.path;
                if (entryName.startsWith(prefix))
                    entryName = entryName.substring(prefix.length());
            }
            return removeExtension(entryName).replace('/', '.');
        }

        @Override
        public boolean isNameCompatible(String cn, Kind k) {
            cn.getClass(); // null check
            if (k == Kind.OTHER && getKind() != k)
                return false;
            return name.equals(cn + k.extension);
        }

        /**
         * Check if two file objects are equal.
         * Two ZipFileIndexFileObjects are equal if the absolute paths of the underlying
         * zip files are equal and if the paths within those zip files are equal.
         */
        @Override
        public boolean equals(Object other) {
            if (this == other)
                return true;

            if (!(other instanceof ZipFileIndexFileObject))
                return false;

            ZipFileIndexFileObject o = (ZipFileIndexFileObject) other;
            return zfIndex.getAbsoluteFile().equals(o.zfIndex.getAbsoluteFile())
                    && name.equals(o.name);
        }

        @Override
        public int hashCode() {
            return zfIndex.getAbsoluteFile().hashCode() + name.hashCode();
        }

        private String getPrefixedEntryName() {
            if (zfIndex.symbolFilePrefix != null)
                return zfIndex.symbolFilePrefix.path + entry.getName();
            else
                return entry.getName();
        }
    }

}
