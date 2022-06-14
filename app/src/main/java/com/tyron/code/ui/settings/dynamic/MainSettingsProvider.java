package com.tyron.code.ui.settings.dynamic;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.tyron.code.R;
import com.tyron.code.ui.settings.AboutUsFragment;

public class MainSettingsProvider implements SettingsProvider{

    public static final String SCREEN_KEY = "MAIN";

    @Override
    public String getScreenKey() {
        return SCREEN_KEY;
    }


    @Override
    public PreferenceScreen createPreferenceScreen(DynamicSettingsFragment fragment) {
        PreferenceManager preferenceManager = fragment.getPreferenceManager();
        Context context = fragment.requireContext();

        PreferenceScreen preferenceScreen = preferenceManager.createPreferenceScreen(context);
        preferenceScreen.setKey(getScreenKey());
        preferenceScreen.setTitle(R.string.menu_settings);

        Preference preference = new Preference(context);
        preference.setIconSpaceReserved(false);
        preference.setTitle(R.string.settings_title_application);
        preference.setSummary(R.string.settings_desc_application);
        preference.setOnPreferenceClickListener(preference1 -> {
            fragment.setScreen("GENERAL");
            return true;
        });
        preferenceScreen.addPreference(preference);

        Preference editorPreference = new Preference(context);
        editorPreference.setIconSpaceReserved(false);
        editorPreference.setTitle(R.string.settings_title_editor);
        editorPreference.setSummary(R.string.settings_desc_editor);
        editorPreference.setOnPreferenceClickListener(preference1 -> {
            fragment.setScreen("EDITOR");
            return true;
        });
        preferenceScreen.addPreference(editorPreference);

        Preference buildPreference = new Preference(context);
        buildPreference.setIconSpaceReserved(false);
        buildPreference.setTitle(R.string.settings_build_run_title);
        buildPreference.setSummary(R.string.settings_build_run_desc);
        buildPreference.setOnPreferenceClickListener(preference1 -> {
            fragment.setScreen("BUILD");
            return true;
        });
        preferenceScreen.addPreference(buildPreference);

        Preference aboutPreference = new Preference(context);
        aboutPreference.setIconSpaceReserved(false);
        aboutPreference.setTitle(R.string.settings_about_us_title);
        aboutPreference.setSummary(R.string.settings_about_us_desc);
        aboutPreference.setFragment(AboutUsFragment.class.getName());
        preferenceScreen.addPreference(aboutPreference);
        return preferenceScreen;
    }
}
