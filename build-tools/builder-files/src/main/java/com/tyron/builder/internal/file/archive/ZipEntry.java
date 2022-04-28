package com.tyron.builder.internal.file.archive;

import java.io.IOException;
import java.io.InputStream;

public interface ZipEntry {

    boolean isDirectory();

    String getName();

    /**
     * This method or {@link #withInputStream(InputStreamAction)} ()} can be called at most once per entry.
     */
    byte[] getContent() throws IOException;

    /**
     * Functional interface to run an action against a {@link InputStream}
     *
     * @param <T> the action's result.
     */
    interface InputStreamAction<T> {
        /**
         * action to run against the passed {@link InputStream}.
         */
        T run(InputStream inputStream) throws IOException;
    }

    /**
     * Declare an action to be run against this ZipEntry's content as a {@link InputStream}.
     * The {@link InputStream} passed to the {@link InputStreamAction#run(InputStream)} will
     * be closed right after the action's return.
     *
     * This method or {@link #getContent()} can be called at most once per entry.
     */
    <T> T withInputStream(InputStreamAction<T> action) throws IOException;

    /**
     * The size of the content in bytes, or -1 if not known.
     */
    int size();
}