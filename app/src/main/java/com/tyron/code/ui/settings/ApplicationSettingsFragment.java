package com.tyron.code.ui.settings;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.SharedPreferencesKt;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.tyron.code.R;
import com.tyron.common.SharedPreferenceKeys;

public class ApplicationSettingsFragment extends PreferenceFragmentCompat {

    public static class ThemeProvider {

        private final Context context;

        public ThemeProvider(Context context) {
            this.context = context;
        }

        public int getThemeFromPreferences() {
            SharedPreferences preferences =
                    PreferenceManager.getDefaultSharedPreferences(context);
            String selectedTheme = preferences.getString("theme", "default");
            return getTheme(selectedTheme);
        }

        public String getDescriptionForTheme(String selectedTheme) {
            switch (selectedTheme) {
                case "light": return context.getString(R.string.settings_theme_value_light);
                case "night": return context.getString(R.string.settings_theme_value_dark);
                default: return context.getString(R.string.settings_theme_value_default);
            }
        }

        private int getTheme(String selectedTheme) {
            switch (selectedTheme) {
                case "light": return AppCompatDelegate.MODE_NIGHT_NO;
                case "dark": return AppCompatDelegate.MODE_NIGHT_YES;
                default: return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
        }
    }
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.application_preferences, rootKey);

        Preference theme = findPreference(SharedPreferenceKeys.THEME);
        assert theme != null;
        theme.setOnPreferenceChangeListener((preference, newValue) -> {
            if (newValue instanceof String) {
                ThemeProvider provider = new ThemeProvider(requireContext());
                int newTheme = provider.getTheme((String) newValue);
                AppCompatDelegate.setDefaultNightMode(newTheme);
                theme.setSummaryProvider(p -> provider.getDescriptionForTheme(String.valueOf(newValue)));
                return true;
            }
            return false;
        });
    }
}
