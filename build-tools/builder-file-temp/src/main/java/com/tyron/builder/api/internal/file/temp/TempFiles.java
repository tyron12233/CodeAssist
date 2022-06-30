package com.tyron.builder.api.internal.file.temp;

import javax.annotation.CheckReturnValue;
import java.io.File;
import java.io.IOException;

/**
 * Security safe API's for creating temporary files.
 */
public final class TempFiles {

    private TempFiles() {
        /* no-op */
    }

    /**
     * Improves the security guarantees of {@link File#createTempFile(String, String, File)}.
     *
     * @see File#createTempFile(String, String, File)
     */
    @CheckReturnValue
    static File createTempFile(String prefix, String suffix, File directory) throws IOException {
        if (directory == null) {
            throw new NullPointerException("The `directory` argument must not be null as this will default to the system temporary directory");
        }
        if(prefix == null) {
            prefix = "gradle-";
        }
        if(prefix.length() <= 3) {
            prefix = "tmp-" + prefix;
        }
        return File.createTempFile(prefix, suffix, directory);
    }
}

