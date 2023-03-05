package com.tyron.builder.dexing;

/**
 * An exception thrown is dex archive merging fails. It is a wrapper exception, to get the actual
 * exception, {@link Throwable#getCause()} should always be invoked. It can also contain an error
 * message that can be retrieved using {@link Throwable#getMessage()}.
 */
public class DexArchiveMergerException extends Exception {
    public DexArchiveMergerException(String message, Throwable cause) {
        super(message, cause);
    }
}