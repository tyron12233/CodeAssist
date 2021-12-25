package com.tyron.code.ui.settings;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import com.github.angads25.filepicker.model.DialogConfigs;
import com.github.angads25.filepicker.model.DialogProperties;
import com.google.android.material.transition.MaterialSharedAxis;
import com.tyron.code.R;
import com.tyron.code.ui.file.FilePickerDialogFixed;
import com.tyron.common.SharedPreferenceKeys;

public class ApplicationSettingsFragment extends PreferenceFragmentCompat
        implements PreferenceManager.OnPreferenceTreeClickListener {
			
    private SharedPreferences mPreferences;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));
        setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.application_preferences, rootKey);
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        String key = preference.getKey();

        switch (key) {
            case "change_project_path":
			    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                showDirectorySelectDialog();
				}
                return true;

            default:
            return true;
        }
    }

    private void showDirectorySelectDialog() {
        DialogProperties properties = new DialogProperties();
        properties.selection_type = DialogConfigs.DIR_SELECT;
        properties.selection_mode = DialogConfigs.SINGLE_MODE;
        properties.root = Environment.getExternalStorageDirectory();
        FilePickerDialogFixed dialogFixed = new FilePickerDialogFixed(requireContext(), properties);
        dialogFixed.setTitle(R.string.project_manager_save_location_title);
        dialogFixed.setDialogSelectionListener(
                files -> {
                    setSavePath(files[0]);
                });
        dialogFixed.show();
    }

    private void setSavePath(String path) {
        mPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        mPreferences.edit().putString(SharedPreferenceKeys.PROJECT_SAVE_PATH, path).apply();
    }
}
