package com.tyron.code.ui.settings.dynamic;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.tyron.code.R;
import com.tyron.common.SharedPreferenceKeys;

public class ApplicationSettingsProvider implements SettingsProvider {

    public static class ThemeProvider {

        private final Context context;

        public ThemeProvider(Context context) {
            this.context = context;
        }

        public int getThemeFromPreferences() {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            String selectedTheme = preferences.getString("theme", "default");
            return getTheme(selectedTheme);
        }

        public String getDescriptionForTheme(String selectedTheme) {
            switch (selectedTheme) {
                case "light":
                    return context.getString(R.string.settings_theme_value_light);
                case "night":
                    return context.getString(R.string.settings_theme_value_dark);
                default:
                    return context.getString(R.string.settings_theme_value_default);
            }
        }

        private int getTheme(String selectedTheme) {
            switch (selectedTheme) {
                case "light":
                    return AppCompatDelegate.MODE_NIGHT_NO;
                case "dark":
                    return AppCompatDelegate.MODE_NIGHT_YES;
                default:
                    return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
        }
    }

    @Override
    public String getScreenKey() {
        return "GENERAL";
    }

    @Override
    public PreferenceScreen createPreferenceScreen(DynamicSettingsFragment fragment) {
        PreferenceManager preferenceManager = fragment.getPreferenceManager();
        Context context = fragment.requireContext();

        PreferenceScreen preferenceScreen = preferenceManager.createPreferenceScreen(context);
        preferenceScreen.setTitle(R.string.settings_title_application);

        PreferenceUtils.addCategory(preferenceScreen, category -> {
            category.setIconSpaceReserved(false);
            category.setTitle("Look and feel");

            ListPreference listPreference = new ListPreference(context);
            listPreference.setTitle(R.string.settings_theme_title);
            listPreference.setEntries(R.array.theme_entries);
            listPreference.setEntryValues(R.array.theme_values);
            listPreference.setIconSpaceReserved(false);
            listPreference.setDefaultValue("default");
            listPreference.setKey(SharedPreferenceKeys.THEME);
            listPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                if (newValue instanceof String) {
                    ThemeProvider provider =
                            new ThemeProvider(context);
                    int newTheme = provider.getTheme((String) newValue);
                    AppCompatDelegate.setDefaultNightMode(newTheme);
                    listPreference.setSummaryProvider(p -> provider.getDescriptionForTheme(String.valueOf(
                            newValue)));
                    return true;
                }
                return false;
            });
            category.addPreference(listPreference);
        });
        return preferenceScreen;
    }
}
