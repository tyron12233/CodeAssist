package com.tyron.builder.tooling.internal.provider.serialization;

import com.tyron.builder.internal.serialize.ExceptionReplacingObjectOutputStream;
import com.tyron.builder.internal.serialize.TopLevelExceptionPlaceholder;

import java.io.IOException;
import java.io.ObjectStreamClass;
import java.io.OutputStream;

class PayloadSerializerObjectOutputStream extends ExceptionReplacingObjectOutputStream {
    static final int SAME_CLASSLOADER_TOKEN = 0;
    private final SerializeMap map;

    public PayloadSerializerObjectOutputStream(OutputStream outputStream, SerializeMap map) throws IOException {
        super(outputStream);
        this.map = map;
    }

    @Override
    protected ExceptionReplacingObjectOutputStream createNewInstance(OutputStream outputStream) throws IOException {
        return new PayloadSerializerObjectOutputStream(outputStream, map);
    }

    @Override
    protected void writeClassDescriptor(ObjectStreamClass desc) throws IOException {
        Class<?> targetClass = desc.forClass();
        writeClass(targetClass);
    }

    @Override
    protected void annotateProxyClass(Class<?> cl) throws IOException {
        writeInt(cl.getInterfaces().length);
        for (Class<?> type : cl.getInterfaces()) {
            writeClass(type);
        }
    }

    private void writeClass(Class<?> targetClass) throws IOException {
        writeClassLoader(targetClass);
        writeUTF(targetClass.getName());
    }

    private void writeClassLoader(Class<?> targetClass) throws IOException {
        if (TopLevelExceptionPlaceholder.class.getPackage().equals(targetClass.getPackage())) {
            writeShort(SAME_CLASSLOADER_TOKEN);
        } else {
            writeShort(map.visitClass(targetClass));
        }
    }
}
