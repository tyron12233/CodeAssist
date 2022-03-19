package com.tyron.builder.compiler.manifest;

import com.tyron.builder.compiler.manifest.xml.IAbstractFile;
import com.tyron.builder.compiler.manifest.xml.IAbstractFolder;
import com.tyron.builder.compiler.manifest.xml.StreamException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ManifestFile implements IAbstractFile {

    private final String contents;

    public ManifestFile(String contents) {
        this.contents = contents;
    }

    @Override
    public InputStream getContents() throws StreamException {
        return new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void setContents(InputStream contents) throws StreamException {

    }

    @Override
    public OutputStream getOutputStream() throws StreamException {
        return null;
    }

    @Override
    public PreferredWriteMode getPreferredWriteMode() {
        return PreferredWriteMode.INPUTSTREAM;
    }

    @Override
    public long getModificationStamp() {
        return 0;
    }

    @Override
    public String getName() {
        return "TEST_FILE";
    }

    @Override
    public String getLocation() {
        return "test";
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public IAbstractFolder getParentFolder() {
        return null;
    }

    @Override
    public boolean delete() {
        return true;
    }
}
