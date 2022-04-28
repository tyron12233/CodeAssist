package com.tyron.builder.compiler.manifest.xml;

public class StreamException extends Exception {

    private static final long serialVersionId = 1L;

    public enum Error {
        DEFAULT, OUTOFSYNC, FILENOTFOUND
    }

    private final Error mError;
    private final IAbstractFile mFile;

    public StreamException(Exception e, IAbstractFile file) {
        this (e, file, Error.DEFAULT);
    }

    public StreamException(Exception e, IAbstractFile file, Error error) {
        super(e);
        mError = error;
        mFile = file;
    }

    public Error getError() {
        return mError;
    }

    public IAbstractFile getFile() {
        return mFile;
    }
}
