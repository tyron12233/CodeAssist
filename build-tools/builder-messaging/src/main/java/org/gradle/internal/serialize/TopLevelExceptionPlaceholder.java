package org.gradle.internal.serialize;

import org.gradle.api.Transformer;

import java.io.OutputStream;

import java.io.IOException;
import java.util.HashSet;

public class TopLevelExceptionPlaceholder extends ExceptionPlaceholder {
    private static final long serialVersionUID = 1L;
    public TopLevelExceptionPlaceholder(Throwable throwable, Transformer<ExceptionReplacingObjectOutputStream, OutputStream> objectOutputStreamCreator) throws IOException {
        super(throwable, objectOutputStreamCreator, new HashSet<Throwable>(10));
    }
}