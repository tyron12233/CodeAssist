package com.tyron.builder.internal.logging.console;


import com.tyron.builder.internal.nativeintegration.console.ConsoleMetaData;

public class ConsoleLayoutCalculator {
    private final ConsoleMetaData consoleMetaData;
    private int maximumAvailableLines = -1;

    /**
     * @param consoleMetaData used to get console dimensions
     */
    public ConsoleLayoutCalculator(ConsoleMetaData consoleMetaData) {
        this.consoleMetaData = consoleMetaData;
    }
    /**
     * Calculate number of Console lines to use for work-in-progress display.
     *
     * @param ideal number of Console lines
     * @return height of progress area.
     */
    public int calculateNumWorkersForConsoleDisplay(int ideal) {
        if (maximumAvailableLines == -1) {
            // Disallow work-in-progress to take up more than half of the console display
            // If the screen size is unknown, allow 4 lines
            int rows = consoleMetaData.getRows();
            maximumAvailableLines = rows == 0 ? 4 : rows / 2;
        }

        return Math.min(ideal, maximumAvailableLines);
    }
}
