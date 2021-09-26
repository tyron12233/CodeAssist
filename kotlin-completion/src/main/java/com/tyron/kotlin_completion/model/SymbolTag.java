package com.tyron.kotlin_completion.model;

public enum SymbolTag {

    Deprecated(1);

    private final int value;

    SymbolTag(int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

    public static SymbolTag forValue(int value) {
        SymbolTag[] allValues = SymbolTag.values();
        if (value < 1 || value > allValues.length) {
            throw new IllegalArgumentException("Illegal enum value: " + value);
        }
        return allValues[value];
    }
}
