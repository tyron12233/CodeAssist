package com.tyron.builder.internal.classloader;

import static com.tyron.builder.internal.UncheckedException.throwAsUncheckedException;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

import static java.lang.ClassLoader.getSystemClassLoader;

import com.tyron.common.TestUtil;

public class ClassLoaderVisitor {
    private static final String JAVA_CLASS_PATH = "java.class.path";
    private final ClassLoader stopAt;

    public ClassLoaderVisitor() {
        this(getSystemClassLoader() == null ? null : getSystemClassLoader().getParent());
    }

    public ClassLoaderVisitor(ClassLoader stopAt) {
        this.stopAt = stopAt;
    }

    public void visit(ClassLoader classLoader) {
        if (classLoader == stopAt) {
            visitSpec(SystemClassLoaderSpec.INSTANCE);
            return;
        }

        if (classLoader instanceof ClassLoaderHierarchy) {
            ((ClassLoaderHierarchy) classLoader).visit(this);
        } else {
            if (TestUtil.isDalvik()) {
                visitClassPath(extractJava9Classpath());
            } else if (isPreJava9LauncherAppClassloader(classLoader)) {
                visitClassPath(extractPreJava9Classpath(classLoader));
            } else {
                visitClassPath(extractJava9Classpath());
            }
            if (classLoader.getParent() != null) {
                visitParent(classLoader.getParent());
            }
        }
    }

    private boolean isPreJava9LauncherAppClassloader(ClassLoader classLoader) {
        return classLoader instanceof URLClassLoader;
    }

    private URL[] extractPreJava9Classpath(ClassLoader classLoader) {
        return ((URLClassLoader) classLoader).getURLs();
    }

    private URL[] extractJava9Classpath() {
        String cp = System.getProperty(JAVA_CLASS_PATH);
        String[] elements = cp.split(File.pathSeparator);

        URL[] urls = new URL[elements.length];
        for (int i = 0; i < elements.length; i++) {
            try {
                URL url = new File(elements[i]).toURI().toURL();
                urls[i] = url;
            } catch (MalformedURLException mue) {
                throw throwAsUncheckedException(mue);
            }
        }
        return urls;
    }

    public void visitSpec(ClassLoaderSpec spec) {
    }

    public void visitClassPath(URL[] classPath) {
    }

    public void visitParent(ClassLoader classLoader) {
        visit(classLoader);
    }
}