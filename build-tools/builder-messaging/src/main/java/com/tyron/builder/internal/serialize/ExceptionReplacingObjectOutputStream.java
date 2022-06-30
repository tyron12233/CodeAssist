package com.tyron.builder.internal.serialize;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.internal.UncheckedException;

import java.io.ObjectOutputStream;
import java.io.OutputStream;

import java.io.IOException;

public class ExceptionReplacingObjectOutputStream extends ObjectOutputStream {
    private Transformer<Object, Object> objectTransformer = new Transformer<Object, Object>() {
        @Override
        public Object transform(Object obj) {
            try {
                return doReplaceObject(obj);
            } catch (IOException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        }
    };

    public ExceptionReplacingObjectOutputStream(OutputStream outputSteam) throws IOException {
        super(outputSteam);
        enableReplaceObject(true);
    }

    public final Transformer<ExceptionReplacingObjectOutputStream, OutputStream> getObjectOutputStreamCreator() {
        return new Transformer<ExceptionReplacingObjectOutputStream, OutputStream>() {
            @Override
            public ExceptionReplacingObjectOutputStream transform(OutputStream outputStream) {
                try {
                    return createNewInstance(outputStream);
                } catch (IOException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        };
    }

    protected ExceptionReplacingObjectOutputStream createNewInstance(OutputStream outputStream) throws IOException {
        return new ExceptionReplacingObjectOutputStream(outputStream);
    }

    @Override
    protected final Object replaceObject(Object obj) throws IOException {
        return getObjectTransformer().transform(obj);
    }

    protected Object doReplaceObject(Object obj) throws IOException {
        if (obj instanceof Throwable) {
            return new TopLevelExceptionPlaceholder((Throwable) obj, getObjectOutputStreamCreator());
        }
        return obj;
    }

    public Transformer<Object, Object> getObjectTransformer() {
        return objectTransformer;
    }

    public void setObjectTransformer(Transformer<Object, Object> objectTransformer) {
        this.objectTransformer = objectTransformer;
    }
}