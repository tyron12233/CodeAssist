package com.flipkart.android.proteus.value;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;

public class Style extends Value {

    private static final String STYLE_PREFIX = "@style/";

    private final String name;
    /** The name of the parent of this style */
    private final String parent;
    private final ObjectValue values = new ObjectValue();

    public Style(@NonNull String name) {
        this.name = name;
        this.parent = null;
    }

    public Style(@NonNull String name, @Nullable String parent) {
        this.name = name;
        this.parent = parent;
    }

    public static boolean isStyle(@NonNull String string) {
        return string.startsWith(STYLE_PREFIX);
    }

    public static Value valueOf(String string, ProteusContext context) {
        return context.getProteusResources().getStyle(string);
    }

    public Value getValue(String name, Value defaultValue) {
        Value value = values.get(name);
        if (value != null) {
            return value;
        }
        return defaultValue;
    }

    public void addValue(String name, String value) {
        values.addProperty(name, value);
    }

    public ObjectValue getValues() {
        return values;
    }

    @Override
    public String toString() {
        return "Style{" + "name='" + name + '\'' + ", parent='" + parent + '\'' + ", values=" + values + '}';
    }

    @Override
    public Value copy() {
        return null;
    }
}
