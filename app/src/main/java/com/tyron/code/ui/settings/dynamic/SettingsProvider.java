package com.tyron.code.ui.settings.dynamic;

import androidx.preference.PreferenceScreen;

public interface SettingsProvider {

    String getScreenKey();

    PreferenceScreen createPreferenceScreen(DynamicSettingsFragment fragment);
}
