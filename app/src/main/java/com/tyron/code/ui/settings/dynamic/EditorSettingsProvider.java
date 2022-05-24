package com.tyron.code.ui.settings.dynamic;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.tyron.code.R;

public class EditorSettingsProvider implements SettingsProvider {

    private static final String SCREEN_KEY = "EDITOR";

    @Override
    public String getScreenKey() {
        return SCREEN_KEY;
    }

    @Override
    public PreferenceScreen createPreferenceScreen(DynamicSettingsFragment fragment) {
        PreferenceManager preferenceManager = fragment.getPreferenceManager();
        PreferenceScreen preferenceScreen = preferenceManager.createPreferenceScreen(fragment.requireContext());
        preferenceScreen.setTitle(R.string.editor_settings_title);

        Preference preference = new EditTextPreference(fragment.requireContext());
        preference.setTitle("Some title");
        preference.setSummary("Some summary");
        preference.setKey("SOME_KEY");
        preferenceScreen.addPreference(preference);

        return preferenceScreen;
    }
}
