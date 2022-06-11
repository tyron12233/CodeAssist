package com.tyron.code.ui.settings.dynamic;

import static com.tyron.code.ui.settings.dynamic.PreferenceUtils.addCategory;

import android.content.Context;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.tyron.code.R;

import java.util.function.Consumer;

public class EditorSettingsProvider implements SettingsProvider {

    private static final String SCREEN_KEY = "EDITOR";

    @Override
    public String getScreenKey() {
        return SCREEN_KEY;
    }

    @Override
    public PreferenceScreen createPreferenceScreen(DynamicSettingsFragment fragment) {
        Context context = fragment.requireContext();

        PreferenceManager preferenceManager = fragment.getPreferenceManager();
        PreferenceScreen preferenceScreen = preferenceManager.createPreferenceScreen(fragment.requireContext());
        preferenceScreen.setTitle(R.string.editor_settings_title);

        addCategory(preferenceScreen, javaCategory -> {
            javaCategory.setIconSpaceReserved(false);
            javaCategory.setTitle(R.string.completion_settings_java_title);

            SwitchPreference errorHighlight = new SwitchPreference(context);
            errorHighlight.setTitle(R.string.code_editor_error_highlight);
            errorHighlight.setKey("code_editor_error_highlight");
            errorHighlight.setDefaultValue("true");
            errorHighlight.setIconSpaceReserved(false);
            javaCategory.addPreference(errorHighlight);

            SwitchPreference codeCompletion = new SwitchPreference(context);
            codeCompletion.setTitle(R.string.settings_code_completions);
            codeCompletion.setKey("code_editor_completion");
            codeCompletion.setDefaultValue("true");
            codeCompletion.setIconSpaceReserved(false);
            javaCategory.addPreference(codeCompletion);

            SwitchPreference caseInsensitiveMatch = new SwitchPreference(context);
            caseInsensitiveMatch.setTitle(R.string.settings_case_insensitive_match_title);
            caseInsensitiveMatch.setSummary(R.string.settings_case_insensitive_match_desc);
            caseInsensitiveMatch.setKey("java_case_insensitive_match");
            caseInsensitiveMatch.setDefaultValue("true");
            caseInsensitiveMatch.setIconSpaceReserved(false);
            javaCategory.addPreference(caseInsensitiveMatch);
        });

        addCategory(preferenceScreen, editor -> {
            editor.setIconSpaceReserved(false);
            editor.setTitle(R.string.editor_settings_title);

            EditTextPreference fontSize = new EditTextPreference(context);
            fontSize.setTitle(R.string.font_size_title);
            fontSize.setSummary(R.string.font_size_summary);
            fontSize.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                editText.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
            });
            fontSize.setNegativeButtonText(android.R.string.cancel);
            fontSize.setPositiveButtonText(android.R.string.ok);
            fontSize.setDialogMessage(R.string.font_size_message);
            fontSize.setKey("font_size");
            fontSize.setDefaultValue("14");
            fontSize.setIconSpaceReserved(false);
            editor.addPreference(fontSize);

            SwitchPreference wordWrap = new SwitchPreference(context);
            wordWrap.setIconSpaceReserved(false);
            wordWrap.setTitle(R.string.editor_settings_wordwrap);
            wordWrap.setKey("editor_wordwrap");
            wordWrap.setDefaultValue("false");
            editor.addPreference(wordWrap);
        });

        addCategory(preferenceScreen, editorTabs -> {
            editorTabs.setTitle(R.string.editor_tabs_settings_title);
            editorTabs.setIconSpaceReserved(false);

            SwitchPreference showNonUniqueFileNames = new SwitchPreference(context);
            showNonUniqueFileNames.setTitle(R.string.editor_tab_unique_file_name);
            showNonUniqueFileNames.setSummary(R.string.editor_tab_unique_file_name_summary);
            showNonUniqueFileNames.setIconSpaceReserved(false);
            showNonUniqueFileNames.setKey("editor_tab_unique_file_name");
            showNonUniqueFileNames.setDefaultValue("true");
            editorTabs.addPreference(showNonUniqueFileNames);
        });

        return preferenceScreen;
    }



}
