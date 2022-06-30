package com.tyron.builder.internal.nativeintegration.console;

public interface ConsoleMetaData {
    /**
     * Returns true if the current process' stdout is attached to the console.
     */
    boolean isStdOut();

    /**
     * Returns true if the current process' stderr is attached to the console.
     */
    boolean isStdErr();

    /**
     * <p>Returns the number of columns available in the console.</p>
     *
     * @return The number of columns available in the console. If no information is available return 0.
     */
    int getCols();

    /**
     * <p>Returns the number of rows available in the console.</p>
     *
     * @return The height of the console (rows). If no information is available return 0.
     */
    int getRows();

    boolean isWrapStreams();
}
