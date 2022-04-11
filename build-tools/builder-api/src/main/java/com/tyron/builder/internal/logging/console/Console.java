package com.tyron.builder.internal.logging.console;

public interface Console {
    TextArea getBuildOutputArea();

    BuildProgressArea getBuildProgressArea();

    // TODO(ew): Consider whether this belongs in BuildProgressArea or here
    StyledLabel getStatusBar();

    /**
     * Flushes any pending updates. Updates may or may not be buffered, and this method should be called to finish rendering and pending updates, such as updating the status bar.
     */
    void flush();
}

