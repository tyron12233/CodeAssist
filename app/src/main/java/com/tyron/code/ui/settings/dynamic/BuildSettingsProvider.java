package com.tyron.code.ui.settings.dynamic;

import static com.tyron.code.ui.settings.dynamic.PreferenceUtils.addCategory;

import android.content.Context;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.tyron.code.R;
import com.tyron.common.SharedPreferenceKeys;

public class BuildSettingsProvider implements SettingsProvider {

    private static final String KEY = "BUILD";

    @Override
    public String getScreenKey() {
        return KEY;
    }

    @Override
    public PreferenceScreen createPreferenceScreen(DynamicSettingsFragment fragment) {
        Context context = fragment.requireContext();

        PreferenceManager preferenceManager = fragment.getPreferenceManager();
        PreferenceScreen preferenceScreen = preferenceManager.createPreferenceScreen(fragment.requireContext());
        preferenceScreen.setTitle(R.string.settings_build_run_title);

        addCategory(preferenceScreen, gradle -> {
           gradle.setTitle("Gradle");
           gradle.setIconSpaceReserved(false);

            ListPreference gradleLogLevel = new ListPreference(context);
            gradleLogLevel.setIconSpaceReserved(false);
            gradleLogLevel.setTitle(R.string.gradle_log_level);
            gradleLogLevel.setEntryValues(R.array.gradle_log_levels);
            gradleLogLevel.setEntries(R.array.gradle_log_levels);
            gradleLogLevel.setDefaultValue("LIFECYCLE");
            gradleLogLevel.setKey(SharedPreferenceKeys.GRADLE_LOG_LEVEL);
            gradle.addPreference(gradleLogLevel);

            ListPreference stacktrace = new ListPreference(context);
            stacktrace.setEntries(R.array.gradle_stacktrace_values);
            stacktrace.setEntryValues(R.array.gradle_stacktrace_values);
            stacktrace.setTitle(R.string.gradle_stacktrace);
            stacktrace.setSummary(R.string.gradle_stacktrace_summary);
            stacktrace.setDefaultValue("INTERNAL_EXCEPTIONS");
            stacktrace.setIconSpaceReserved(false);
            stacktrace.setKey(SharedPreferenceKeys.GRADLE_STACKTRACE_MODE);
            gradle.addPreference(stacktrace);

            SwitchPreference verboseVfsLogging = new SwitchPreference(context);
            verboseVfsLogging.setDefaultValue(false);
            verboseVfsLogging.setTitle(R.string.gradle_verbose_vfs_logging);
            verboseVfsLogging.setSummary(R.string.gradle_verbose_vfs_logging_summary);
            verboseVfsLogging.setKey(SharedPreferenceKeys.GRADLE_VERBOSE_VFS_LOGGING);
            verboseVfsLogging.setIconSpaceReserved(false);
            gradle.addPreference(verboseVfsLogging);


        });
        return preferenceScreen;
    }
}
