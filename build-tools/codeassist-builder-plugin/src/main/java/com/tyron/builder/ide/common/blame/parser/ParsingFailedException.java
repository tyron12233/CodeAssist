package com.tyron.builder.ide.common.blame.parser;

import com.android.annotations.NonNull;

/**
 * Indicates that an error occurred while parsing the output of a compiler.
 */
public class ParsingFailedException extends Exception {

    public ParsingFailedException() {
    }

    public ParsingFailedException(@NonNull Throwable cause) {
        super(cause);
    }
}