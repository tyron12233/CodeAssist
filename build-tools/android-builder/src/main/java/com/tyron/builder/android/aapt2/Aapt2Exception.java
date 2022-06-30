package com.tyron.builder.android.aapt2;

/** Exception thrown when an error occurs during `aapt2` processing.  */
public class Aapt2Exception extends RuntimeException {

    private static final long serialVersionUID = 7034893190645766936L;

    public static Aapt2Exception create(
            String description
    ) {
        return new Aapt2Exception(description, null, null, null, null);
    }

    public static Aapt2Exception create(
            String description,
            Throwable cause,
            String output,
            String processName,
            String command
    ) {
        return new Aapt2Exception(description, cause, output, processName, command);
    }

    public Aapt2Exception(
            String description,
            Throwable cause,
            String output,
            String processName,
            String command
    ) {
        super(makeMessage(description, output), cause);
    }


    private static String makeMessage(String description, String output) {
        if (output == null) {
            return description;
        }
        return description + "\n" + output;
    }
}