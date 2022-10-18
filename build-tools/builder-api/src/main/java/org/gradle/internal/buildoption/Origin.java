package org.gradle.internal.buildoption;

import org.apache.commons.lang3.StringUtils;
import org.gradle.cli.CommandLineArgumentException;

public abstract class Origin {
    protected String source;

    public static Origin forGradleProperty(String gradleProperty) {
        return new GradlePropertyOrigin(gradleProperty);
    }

    public static Origin forCommandLine(String commandLineOption) {
        return new CommandLineOrigin(commandLineOption);
    }

    private Origin(String source) {
        this.source = source;
    }

    public abstract void handleInvalidValue(String value, String hint);

    public void handleInvalidValue(String value) {
        handleInvalidValue(value, null);
    }

    String hintMessage(String hint) {
        if (StringUtils.isBlank(hint)) {
            return "";
        }
        return String.format(" (%s)", hint);
    }

    private static class GradlePropertyOrigin extends Origin {
        public GradlePropertyOrigin(String value) {
            super(value);
        }

        @Override
        public void handleInvalidValue(String value, String hint) {
            String message = String.format("Value '%s' given for %s Gradle property is invalid%s", value, source, hintMessage(hint));
            throw new IllegalArgumentException(message);
        }
    }

    private static class CommandLineOrigin extends Origin {
        public CommandLineOrigin(String value) {
            super(value);
        }

        @Override
        public void handleInvalidValue(String value, String hint) {
            String message = String.format("Argument value '%s' given for --%s option is invalid%s", value, source, hintMessage(hint));
            throw new CommandLineArgumentException(message);
        }
    }
}