package com.tyron.builder.api.logging.configuration;

/**
 * Specifies how to treat color and dynamic console output.
 */
public enum ConsoleOutput {
    /**
     * Disable all color and rich output. Generate plain text only.
     */
    Plain,
    /**
     * Enable color and rich output when the current process is attached to a console, disable when not attached to a console.
     */
    Auto,
    /**
     * Enable color and rich output, regardless of whether the current process is attached to a console or not.
     * When not attached to a console, the color and rich output is encoded using ANSI control characters.
     */
    Rich,
    /**
     * Enable color and rich output like Rich, but output more detailed message.
     *
     * @since 4.3
     */
    Verbose
}