package com.tyron.builder.internal.typeconversion;

import com.tyron.builder.api.InvalidUserDataException;

import java.util.concurrent.TimeUnit;

import static com.tyron.builder.internal.typeconversion.NormalizedTimeUnit.millis;

public class TimeUnitsParser {

    public NormalizedTimeUnit parseNotation(CharSequence notation, int value) {
        String candidate = notation.toString().toUpperCase();
        //jdk5 does not have days, hours or minutes, normalizing to millis
        switch (candidate) {
            case "DAYS":
                return millis(value * 24 * 60 * 60 * 1000);
            case "HOURS":
                return millis(value * 60 * 60 * 1000);
            case "MINUTES":
                return millis(value * 60 * 1000);
        }
        try {
            return new NormalizedTimeUnit(value, TimeUnit.valueOf(candidate));
        } catch (Exception e) {
            throw new InvalidUserDataException("Unable to parse provided TimeUnit: " + notation, e);
        }
    }
}
