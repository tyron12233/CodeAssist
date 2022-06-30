package com.tyron.code.ui.settings.dynamic;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import java.util.function.Consumer;

public class PreferenceUtils {

    public static void addCategory(PreferenceScreen screen, Consumer<PreferenceCategory> consumer) {
        PreferenceCategory category = new PreferenceCategory(screen.getContext());
        screen.addPreference(category);
        consumer.accept(category);
    }
}
