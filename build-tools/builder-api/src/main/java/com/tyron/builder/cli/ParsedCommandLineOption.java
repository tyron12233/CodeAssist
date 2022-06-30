package com.tyron.builder.cli;

import java.util.ArrayList;
import java.util.List;

public class ParsedCommandLineOption {
    private final List<String> values = new ArrayList<String>();

    public String getValue() {
        if (!hasValue()) {
            throw new IllegalStateException("Option does not have any value.");
        }
        if (values.size() > 1) {
            throw new IllegalStateException("Option has multiple values.");
        }
        return values.get(0);
    }

    public List<String> getValues() {
        return values;
    }

    public void addArgument(String argument) {
        values.add(argument);
    }

    public boolean hasValue() {
        return !values.isEmpty();
    }
}
