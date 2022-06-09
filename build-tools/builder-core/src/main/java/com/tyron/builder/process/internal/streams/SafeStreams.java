package com.tyron.builder.process.internal.streams;

import org.apache.commons.io.output.CloseShieldOutputStream;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class SafeStreams {

    public static OutputStream systemErr() {
        return new CloseShieldOutputStream(System.err);
    }

    public static InputStream emptyInput() {
        return new ByteArrayInputStream(new byte[0]);
    }

    public static OutputStream systemOut() {
        return new CloseShieldOutputStream(System.out);
    }
}
