package com.tyron.builder.cli;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CommandLineOption {
    private final Set<String> options = new HashSet<String>();
    private Class<?> argumentType = Void.TYPE;
    private String description;
    private boolean incubating;
    private final Set<CommandLineOption> groupWith = new HashSet<CommandLineOption>();
    private boolean deprecated;

    public CommandLineOption(Iterable<String> options) {
        for (String option : options) {
            this.options.add(option);
        }
    }

    public Set<String> getOptions() {
        return options;
    }

    public CommandLineOption hasArgument(Class<?> argumentType) {
        this.argumentType = argumentType;
        return this;
    }

    public CommandLineOption hasArgument() {
        this.argumentType = String.class;
        return this;
    }

    public CommandLineOption hasArguments() {
        argumentType = List.class;
        return this;
    }

    public String getDescription() {
        StringBuilder result = new StringBuilder();
        if (description != null) {
            result.append(description);
        }

        appendMessage(result, deprecated, "[deprecated]");
        appendMessage(result, incubating, "[incubating]");

        return result.toString();
    }

    private void appendMessage(StringBuilder result, boolean append, String message) {
        if (append) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(message);
        }
    }

    public CommandLineOption hasDescription(String description) {
        this.description = description;
        return this;
    }

    public boolean getAllowsArguments() {
        return argumentType != Void.TYPE;
    }

    public boolean getAllowsMultipleArguments() {
        return argumentType == List.class;
    }

    public CommandLineOption deprecated() {
        this.deprecated = true;
        return this;
    }

    public CommandLineOption incubating() {
        incubating = true;
        return this;
    }

    public boolean isDeprecated() {
        return deprecated;
    }

    public boolean isIncubating() {
        return incubating;
    }

    Set<CommandLineOption> getGroupWith() {
        return groupWith;
    }

    void groupWith(Set<CommandLineOption> options) {
        this.groupWith.addAll(options);
        this.groupWith.remove(this);
    }
}

