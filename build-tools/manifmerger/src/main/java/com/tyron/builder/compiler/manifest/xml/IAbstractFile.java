package com.tyron.builder.compiler.manifest.xml;

import java.io.InputStream;
import java.io.OutputStream;

public interface IAbstractFile extends IAbstractResource {
    enum PreferredWriteMode {
        INPUTSTREAM, OUTPUTSTREAM
    }

    InputStream getContents() throws StreamException;

    void setContents(InputStream contents) throws StreamException;

    OutputStream getOutputStream() throws StreamException;

    PreferredWriteMode getPreferredWriteMode();

    long getModificationStamp();
}
