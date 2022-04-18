package com.tyron.builder.api.logging.configuration;

/**
 * Specifies the warning mode a user wants to see.
 *
 * @since 4.5
 */
public enum WarningMode {
    /**
     * Show all warnings.
     */
    All(true),

    /**
     * Display a summary at the end of the build instead of rendering all warnings into the console output.
     */
    Summary(false),

    /**
     * No deprecation warnings at all.
     */
    None(false),

    /**
     * Show all warnings and fail the build if any warning present
     *
     * @since 5.6
     */
    Fail(true);

    private boolean displayMessages;

    WarningMode(boolean displayMessages) {
        this.displayMessages = displayMessages;
    }

    /**
     * Indicates whether deprecation messages are to be printed in-line
     *
     * @return {@code true} if messages are to be printed, {@code false} otherwise
     *
     * @since 5.6
     */
    public boolean shouldDisplayMessages() {
        return displayMessages;
    }
}