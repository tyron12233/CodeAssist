package com.tyron.builder.util.internal;

import com.tyron.builder.api.InvalidUserDataException;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;

public final class NameValidator {

    private static final char[] FORBIDDEN_CHARACTERS = new char[] {'/', '\\', ':', '<', '>', '"', '?', '*', '|'};
    private static final char FORBIDDEN_LEADING_AND_TRAILING_CHARACTER = '.';

    private NameValidator() { }

    /**
     * Validates that a given name string does not contain any forbidden characters.
     */
    public static void validate(String name, String nameDescription, String fixSuggestion) throws InvalidUserDataException {
        if (StringUtils.isEmpty(name)) {
            throw newInvalidUserDataException("The " + nameDescription + " must not be empty.", fixSuggestion);
        } else if (StringUtils.containsAny(name, FORBIDDEN_CHARACTERS)) {
            throw newInvalidUserDataException("The " + nameDescription + " '" + name + "' must not contain any of the following characters: " + Arrays.toString(FORBIDDEN_CHARACTERS) + ".", fixSuggestion);
        } else if (name.charAt(0) == FORBIDDEN_LEADING_AND_TRAILING_CHARACTER || name.charAt(name.length() - 1) == FORBIDDEN_LEADING_AND_TRAILING_CHARACTER) {
            throw newInvalidUserDataException("The " + nameDescription + " '" + name + "' must not start or end with a '" + FORBIDDEN_LEADING_AND_TRAILING_CHARACTER + "'.", fixSuggestion);
        }
    }

    private static InvalidUserDataException newInvalidUserDataException(String message, String fixSuggestion) {
        return new InvalidUserDataException(message + (StringUtils.isBlank(fixSuggestion) ? "" : (" " + fixSuggestion)));
    }
}