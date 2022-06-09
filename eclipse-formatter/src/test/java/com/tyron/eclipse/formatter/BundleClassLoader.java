package com.tyron.eclipse.formatter;

import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleReference;

import java.io.File;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import jdk.internal.loader.ClassLoaders;
import jdk.internal.loader.URLClassPath;

public class BundleClassLoader extends BlockJUnit4ClassRunner {

    public BundleClassLoader(Class<?> testClass) throws InitializationError {
        super(getTestClass(testClass));
    }

    private static Class<?> getTestClass(Class<?> clazz) throws InitializationError {
        try {
            String classpath = System.getProperty("java.class.path");
            String[] split = classpath.split(";");
            List<URL> list = new ArrayList<>();
            for (String s : split) {
                File file = new File(s);
                URL toURL = file.toURL();
                list.add(toURL);
            }
            URL[] urls = list.toArray(new URL[0]);
            TestClassLoader classLoader = new TestClassLoader(urls);
            return classLoader.loadClass(clazz.getName());
        } catch (Exception | LinkageError e) {
            throw new InitializationError(e);
        }
    }

    public static class TestClassLoader extends URLClassLoader implements BundleReference {

        private Bundle bundle;

        public TestClassLoader(URL[] urls) {
            super(urls);
        }

        public TestClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (!name.startsWith("org.junit.")) {
                try {
                    Class<?> aClass = findClass(name);
                    if (aClass != null) {
                        return aClass;
                    }
                } catch (Throwable ignored) {

                }
            }
            return super.loadClass(name);
        }

        @Override
        public Bundle getBundle() {
            return bundle;
        }

        public void setBundle(Bundle bundle) {
            this.bundle = bundle;
        }
    }
}
