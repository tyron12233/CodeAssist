package com.tyron.builder.api.internal.typeconversion;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Formatter;

public class UnsupportedNotationException extends TypeConversionException {
    private final Object notation;
    private final Collection<String> candidates;

    public UnsupportedNotationException(Object notation) {
        super("Could not convert " + notation);
        this.notation = notation;
        this.candidates = Collections.emptyList();
    }

    public UnsupportedNotationException(Object notation, String failure, @Nullable String resolution, Collection<String> candidateTypes) {
        super(format(failure, resolution, candidateTypes));
        this.notation = notation;
        this.candidates = candidateTypes;
    }

    public Collection<String> getCandidates() {
        return candidates;
    }

    private static String format(String failure, String resolution, Collection<String> formats) {
        Formatter message = new Formatter();
        message.format("%s%n", failure);
        message.format("The following types/formats are supported:");
        for (String format : formats) {
            message.format("%n  - %s", format);
        }
        if (isTrue(resolution)) {
            message.format("%n%n%s", resolution);
        }
        return message.toString();
    }

    private static boolean isTrue(String resolution) {
        if (resolution == null) {
            return false;
        }
        return resolution.length() > 0;
    }

    public Object getNotation() {
        return notation;
    }
}