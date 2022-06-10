package org.gradle.caching.internal.origin;


import java.io.IOException;
import java.io.OutputStream;

public interface OriginWriter {
    void execute(OutputStream outputStream) throws IOException;
}