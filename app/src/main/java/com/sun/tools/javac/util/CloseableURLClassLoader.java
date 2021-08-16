/*
 * Copyright (c) 2007, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.util;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.jar.JarFile;

/**
 * A URLClassLoader that also implements Closeable.
 * Reflection is used to access internal data structures in the URLClassLoader,
 * since no public API exists for this purpose. Therefore this code is somewhat
 * fragile. Caveat emptor.
 * @throws Error if the internal data structures are not as expected.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class CloseableURLClassLoader
        extends URLClassLoader implements Closeable {
    public CloseableURLClassLoader(URL[] urls, ClassLoader parent) throws Error {
        super(urls, parent);
        try {
            getLoaders(); //proactive check that URLClassLoader is as expected
        } catch (Throwable t) {
            throw new Error("cannot create CloseableURLClassLoader", t);
        }
    }

    /**
     * Close any jar files that may have been opened by the class loader.
     * Reflection is used to access the jar files in the URLClassLoader's
     * internal data structures.
     * @throws IOException if the jar files cannot be found for any
     * reson, or if closing the jar file itself causes an IOException.
     */
    @Override
    public void close() throws IOException {
        try {
            for (Object l: getLoaders()) {
                if (l.getClass().getName().equals("sun.misc.URLClassPath$JarLoader")) {
                    Field jarField = l.getClass().getDeclaredField("jar");
                    JarFile jar = (JarFile) getField(l, jarField);
                    if (jar != null) {
                        //System.err.println("CloseableURLClassLoader: closing " + jar);
                        jar.close();
                    }
                }
            }
        } catch (Throwable t) {
            IOException e = new IOException("cannot close class loader");
            e.initCause(t);
            throw e;
        }
    }

    private ArrayList<?> getLoaders()
            throws NoSuchFieldException, IllegalArgumentException, IllegalAccessException
    {
        Field ucpField = URLClassLoader.class.getDeclaredField("ucp");
        Object urlClassPath = getField(this, ucpField);
        if (urlClassPath == null)
            throw new AssertionError("urlClassPath not set in URLClassLoader");
        Field loadersField = urlClassPath.getClass().getDeclaredField("loaders");
        return (ArrayList<?>) getField(urlClassPath, loadersField);
    }

    private Object getField(Object o, Field f)
            throws IllegalArgumentException, IllegalAccessException {
        boolean prev = f.isAccessible();
        try {
            f.setAccessible(true);
            return f.get(o);
        } finally {
            f.setAccessible(prev);
        }
    }

}
