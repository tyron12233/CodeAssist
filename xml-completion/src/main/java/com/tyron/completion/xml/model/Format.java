package com.tyron.completion.xml.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A format type for an attribute
 */
public enum Format {
    /**
     * A string attribute
     */
    STRING,

    /**
     * A boolean attribute may be {@code true} or {@code false}
     */
    BOOLEAN,

    INTEGER,

    FRACTION,

    FLOAT,

    COLOR,

    /**
     * An attribute that references another attribute such as {@code @color/purple}
     */
    REFERENCE,

    /**
     * A dimension attribute which is density independent
     */
    DIMENSION,

    /**
     * An attribute that only supports values defined in an enum. Example of this is the
     * {@code android:visibility} attribute which only takes visible, gone and invisible
     */
    ENUM,

    /**
     * An attribute that can have multiple values separated by {@code |}
     */
    FLAG;

    /**
     * Parse the formats from a {@code format=""} declaration
     */
    public static List<Format> fromString(String declaration) {
        String[] split = declaration.split("\\|");
        if (split.length == 0) {
            return Collections.singletonList(fromSingleString(declaration));
        }
        List<Format> formats = new ArrayList<>();
        for (String s : split) {
            Format format = fromSingleString(s);
            if (format != null) {
                formats.add(format);
            }
        }
        return formats;
    }

    private static Format fromSingleString(String string) {
        switch (string.toLowerCase()) {
            case "string": return Format.STRING;
            case "boolean": return Format.BOOLEAN;
            case "dimension": return Format.DIMENSION;
            case "integer": return Format.INTEGER;
            case "float": return Format.FLOAT;
            case "fraction": return Format.FRACTION;
            case "enum": return Format.ENUM;
            case "color": return Format.COLOR;
            case "flag":
            case "flags": return Format.FLAG;
            case "reference": return Format.REFERENCE;
        }
        return null;
    }
}
