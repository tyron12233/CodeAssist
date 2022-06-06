package com.tyron.builder.internal.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;

public class ClassLoaderObjectInputStream extends ObjectInputStream {
    private final ClassLoader loader;

    public ClassLoaderObjectInputStream(InputStream in, ClassLoader loader) throws IOException {
        super(in);
        this.loader = loader;
    }

    public ClassLoader getClassLoader() {
        return loader;
    }

    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
        try {
            return Class.forName(desc.getName(), false, loader);
        } catch (ClassNotFoundException e) {
            return super.resolveClass(desc);
        }
    }
}
