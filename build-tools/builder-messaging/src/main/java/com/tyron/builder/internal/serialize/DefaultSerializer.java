package com.tyron.builder.internal.serialize;

import com.google.common.base.Objects;
import com.tyron.builder.internal.Cast;

import org.apache.commons.io.input.ClassLoaderObjectInputStream;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.StreamCorruptedException;

public class DefaultSerializer<T> extends AbstractSerializer<T> {
    private ClassLoader classLoader;

    public DefaultSerializer() {
        classLoader = getClass().getClassLoader();
    }

    public DefaultSerializer(ClassLoader classLoader) {
        this.classLoader = classLoader != null ? classLoader : getClass().getClassLoader();
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public T read(Decoder decoder) throws Exception {
        try {
            return Cast.uncheckedNonnullCast(new ClassLoaderObjectInputStream(classLoader, decoder.getInputStream()).readObject());
        } catch (StreamCorruptedException e) {
            return null;
        }
    }

    @Override
    public void write(Encoder encoder, T value) throws IOException {
        ObjectOutputStream objectStr = new ObjectOutputStream(encoder.getOutputStream());
        objectStr.writeObject(value);
        objectStr.flush();
    }

    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) {
            return false;
        }

        DefaultSerializer<?> rhs = (DefaultSerializer<?>) obj;
        return Objects.equal(classLoader, rhs.classLoader);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), classLoader);
    }
}