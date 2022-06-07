package com.tyron.terminalview;

/**
 * Debug settings.
 */

class EmulatorDebug {
    /**
     * Set to true to add debugging code and logging.
     */
    public static final boolean DEBUG = false;

    /**
     * Set to true to log IME calls.
     */
    public static final boolean LOG_IME = DEBUG & false;

    /**
     * Set to true to log each character received from the remote process to the
     * android log, which makes it easier to debug some kinds of problems with
     * emulating escape sequences and control codes.
     */
    public static final boolean LOG_CHARACTERS_FLAG = DEBUG & false;

    /**
     * Set to true to log unknown escape sequences.
     */
    public static final boolean LOG_UNKNOWN_ESCAPE_SEQUENCES = DEBUG & false;

    /**
     * The tag we use when logging, so that our messages can be distinguished
     * from other messages in the log. Public because it's used by several
     * classes.
     */
    public static final String LOG_TAG = "EmulatorView";

    public static String bytesToString(byte[] data, int base, int length) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < length; i++) {
            byte b = data[base + i];
            if (b < 32 || b > 126) {
                buf.append(String.format("\\x%02x", b));
            } else {
                buf.append((char)b);
            }
        }
        return buf.toString();
    }
}