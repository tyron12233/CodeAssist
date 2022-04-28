package com.tyron.builder.internal.serialize;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

public abstract class Message {
    /**
     * Serialize the <code>message</code> onto the provided <code>outputStream</code>, replacing all {@link Throwable}s in the object graph with a placeholder object that can be read back by {@link
     * #receive(java.io.InputStream, ClassLoader)}.
     *
     * @param message object to serialize
     * @param outputSteam stream to serialize onto
     */
    public static void send(Object message, OutputStream outputSteam) throws IOException {
        ObjectOutputStream oos = new ExceptionReplacingObjectOutputStream(outputSteam);
        try {
            oos.writeObject(message);
        } finally {
            oos.flush();
        }
    }

    /**
     * Read back an object from the provided stream that has been serialized by a call to {@link #send(Object, java.io.OutputStream)}. Any {@link Throwable} that cannot be de-serialized (for whatever
     * reason) will be replaced by a {@link PlaceholderException}.
     *
     * @param inputSteam stream to read the object from
     * @param classLoader loader used to load exception classes
     * @return the de-serialized object
     */
    public static Object receive(InputStream inputSteam, ClassLoader classLoader)
            throws IOException, ClassNotFoundException {
        ObjectInputStream ois = new ExceptionReplacingObjectInputStream(inputSteam, classLoader);
        return ois.readObject();
    }

}
