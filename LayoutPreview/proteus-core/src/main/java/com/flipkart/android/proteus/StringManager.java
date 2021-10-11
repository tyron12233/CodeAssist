package com.flipkart.android.proteus;

import androidx.annotation.Nullable;

import com.flipkart.android.proteus.value.Value;

import java.util.Locale;
import java.util.Map;

public abstract class StringManager {

    /**
     * Subclasses must provide the strings found on the given tag
     * @param tag the suffix of the values folder, eg. values-en, if null the default value folder
     *            should be used
     * @return Map of Strings with their names as key
     */
    public abstract Map<String, Value> getStrings(@Nullable String tag);

    @Nullable
    public Value get(String name, Locale locale) {
        String tag = locale.toLanguageTag();
        // Try first with the language specific value
        if (getStrings(tag) != null && getStrings(tag).get(name) != null) {
            return getStrings(tag).get(name);
        }

        // fallback to the values folder
        return getStrings(null) != null ? getStrings(null).get(name) : null;
    }
}
