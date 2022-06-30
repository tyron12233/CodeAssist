package com.tyron.builder.internal.serialize;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.internal.UncheckedException;

import org.apache.commons.io.input.ClassLoaderObjectInputStream;

import java.io.InputStream;

import java.io.IOException;

public class ExceptionReplacingObjectInputStream extends ClassLoaderObjectInputStream {

    private final ClassLoader classLoader;

    private Transformer<Object, Object> objectTransformer = new Transformer<Object, Object>() {
        @Override
        public Object transform(Object o) {
            try {
                return doResolveObject(o);
            } catch (IOException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        }
    };

    public ExceptionReplacingObjectInputStream(InputStream inputSteam, ClassLoader classLoader) throws IOException {
        super(classLoader, inputSteam);
        this.classLoader = classLoader;
        enableResolveObject(true);
    }

    public final Transformer<ExceptionReplacingObjectInputStream, InputStream> getObjectInputStreamCreator() {
        return new Transformer<ExceptionReplacingObjectInputStream, InputStream>() {
            @Override
            public ExceptionReplacingObjectInputStream transform(InputStream inputStream) {
                try {
                    return createNewInstance(inputStream);
                } catch (IOException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        };
    }

    protected ExceptionReplacingObjectInputStream createNewInstance(InputStream inputStream) throws IOException {
        return new ExceptionReplacingObjectInputStream(inputStream, classLoader);
    }

    @Override
    protected final Object resolveObject(Object obj) throws IOException {
        return getObjectTransformer().transform(obj);
    }

    protected Object doResolveObject(Object obj) throws IOException {
        if (obj instanceof TopLevelExceptionPlaceholder) {
            return ((ExceptionPlaceholder) obj).read(getClassNameTransformer(), getObjectInputStreamCreator());
        }
        return obj;
    }

    protected final Transformer<Class<?>, String> getClassNameTransformer() {
        return new Transformer<Class<?>, String>() {
            @Override
            public Class<?> transform(String type) {
                try {
                    return lookupClass(type);
                } catch (ClassNotFoundException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        };
    }

    protected Class<?> lookupClass(String type) throws ClassNotFoundException {
        return classLoader.loadClass(type);
    }

    public Transformer<Object, Object> getObjectTransformer() {
        return objectTransformer;
    }

    public void setObjectTransformer(Transformer<Object, Object> objectTransformer) {
        this.objectTransformer = objectTransformer;
    }
}
