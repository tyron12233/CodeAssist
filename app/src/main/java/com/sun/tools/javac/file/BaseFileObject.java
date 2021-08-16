/*
 * Copyright (c) 2005, 2009, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.tools.javac.util.BaseFileManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.CharsetDecoder;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;

/**
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own risk.
 * This code and its internal interfaces are subject to change or
 * deletion without notice.</b>
*/
public abstract class BaseFileObject implements JavaFileObject {
    protected BaseFileObject(JavacFileManager fileManager) {
        this.fileManager = fileManager;
    }

    /** Return a short name for the object, such as for use in raw diagnostics
     */
    public abstract String getShortName();

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + getName() + "]";
    }

    public NestingKind getNestingKind() { return null; }

    public Modifier getAccessLevel()  { return null; }

    public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
        return new InputStreamReader(openInputStream(), getDecoder(ignoreEncodingErrors));
    }

    protected CharsetDecoder getDecoder(boolean ignoreEncodingErrors) {
        throw new UnsupportedOperationException();
    }

    protected abstract String inferBinaryName(Iterable<? extends File> path);

    protected static Kind getKind(String filename) {
        return BaseFileManager.getKind(filename);
    }

    protected static String removeExtension(String fileName) {
        int lastDot = fileName.lastIndexOf(".");
        return (lastDot == -1 ? fileName : fileName.substring(0, lastDot));
    }

    protected static URI createJarUri(File jarFile, String entryName) {
        URI jarURI = jarFile.toURI().normalize();
        String separator = entryName.startsWith("/") ? "!" : "!/";
        try {
            // The jar URI convention appears to be not to re-encode the jarURI
            return new URI("jar:" + jarURI + separator + entryName);
        } catch (URISyntaxException e) {
            throw new CannotCreateUriError(jarURI + separator + entryName, e);
        }
    }

    /** Used when URLSyntaxException is thrown unexpectedly during
     *  implementations of (Base)FileObject.toURI(). */
    protected static class CannotCreateUriError extends Error {
        private static final long serialVersionUID = 9101708840997613546L;
        public CannotCreateUriError(String value, Throwable cause) {
            super(value, cause);
        }
    }

    /** Return the last component of a presumed hierarchical URI.
     *  From the scheme specific part of the URI, it returns the substring
     *  after the last "/" if any, or everything if no "/" is found.
     */
    public static String getSimpleName(FileObject fo) {
        URI uri = fo.toUri();
        String s = uri.getSchemeSpecificPart();
        return s.substring(s.lastIndexOf("/") + 1); // safe when / not found

    }

    // force subtypes to define equals
    @Override
    public abstract boolean equals(Object other);

    // force subtypes to define hashCode
    @Override
    public abstract int hashCode();

    /** The file manager that created this JavaFileObject. */
    protected final JavacFileManager fileManager;
}
