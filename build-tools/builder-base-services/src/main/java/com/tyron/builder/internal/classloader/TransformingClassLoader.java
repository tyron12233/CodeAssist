package com.tyron.builder.internal.classloader;

import com.google.common.io.ByteStreams;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.internal.classpath.ClassPath;

import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.Collection;

public abstract class TransformingClassLoader extends VisitableURLClassLoader {
    static {
        try {
            ClassLoader.registerAsParallelCapable();
        } catch (NoSuchMethodError ignore) {
            // Not supported on Java 6
        }
    }

    public TransformingClassLoader(String name, ClassLoader parent, ClassPath classPath) {
        super(name, parent, classPath);
    }

    public TransformingClassLoader(String name, ClassLoader parent, Collection<URL> urls) {
        super(name, parent, urls);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!shouldTransform(name)) {
            return super.findClass(name);
        }

        String resourceName = name.replace('.', '/') + ".class";
        URL resource = findResource(resourceName);

        byte[] bytes;
        CodeSource codeSource;
        try {
            if (resource != null) {
                bytes = loadBytecode(resource);
                bytes = transform(name, bytes);
                URL codeBase = ClasspathUtil.getClasspathForResource(resource, resourceName).toURI().toURL();
                codeSource = new CodeSource(codeBase, (Certificate[]) null);
            } else {
                bytes = generateMissingClass(name);
                codeSource = null;
            }
        } catch (Exception e) {
            throw new BuildException(String.format("Could not load class '%s' from %s.", name, resource), e);
        }

        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }

        String packageName = StringUtils.substringBeforeLast(name, ".");
        @SuppressWarnings("deprecation") Package p = getPackage(packageName);
        if (p == null) {
            definePackage(packageName, null, null, null, null, null, null, null);
        }
        return defineClass(name, bytes, 0, bytes.length, codeSource);
    }

    @Nullable
    protected byte[] generateMissingClass(String name) {
        return null;
    }

    private byte[] loadBytecode(URL resource) throws IOException {
        try (InputStream inputStream = resource.openStream()) {
            return ByteStreams.toByteArray(inputStream);
        }
    }

    protected boolean shouldTransform(String className) {
        return true;
    }

    protected abstract byte[] transform(String className, byte[] bytes);
}
