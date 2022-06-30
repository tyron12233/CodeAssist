package com.tyron.builder.util.internal;

import java.util.ArrayList;
import java.util.List;

public class ArgumentsSplitter {

    /**
     * Splits the arguments string (for example, a program command line) into a collection.
     * Only supports space-delimited and/or quoted command line arguments. This currently does not handle escaping characters such as quotes.
     *
     * @param arguments the arguments, for example command line args.
     * @return separate command line arguments.
     */
    public static List<String> split(String arguments) {
        List<String> commandLineArguments = new ArrayList<String>();

        Character currentQuote = null;
        StringBuilder currentOption = new StringBuilder();
        boolean hasOption = false;

        for (int index = 0; index < arguments.length(); index++) {
            char c = arguments.charAt(index);
            if (currentQuote == null && Character.isWhitespace(c)) {
                if (hasOption) {
                    commandLineArguments.add(currentOption.toString());
                    hasOption = false;
                    currentOption.setLength(0);
                }
            } else if (currentQuote == null && (c == '"' || c == '\'')) {
                currentQuote = c;
                hasOption = true;
            } else if (currentQuote != null && c == currentQuote) {
                currentQuote = null;
            } else {
                currentOption.append(c);
                hasOption = true;
            }
        }

        if (hasOption) {
            commandLineArguments.add(currentOption.toString());
        }

        return commandLineArguments;
    }
}
