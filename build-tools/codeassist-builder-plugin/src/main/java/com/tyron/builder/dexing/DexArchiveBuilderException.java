package com.tyron.builder.dexing;

/** Exception thrown if something goes wrong when building a dex archive. */
public class DexArchiveBuilderException extends RuntimeException {

    public DexArchiveBuilderException(String message, Throwable cause) {
        super(message, cause);
    }

    public DexArchiveBuilderException(Throwable cause) {
        super(cause);
    }
}