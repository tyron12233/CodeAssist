package com.tyron.builder.internal.io;

import javax.annotation.Nullable;

public interface TextStream {
    /**
     * Called when some chunk of text is available.
     */
    void text(String text);

    /**
     * Called when the end of the stream is reached for some reason.
     *
     * @param failure The failure, if any, which caused the end of the stream.
     */
    void endOfStream(@Nullable Throwable failure);
}

