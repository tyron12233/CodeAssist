package com.tyron.code.ui.main.action.other;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.code.R;
import com.tyron.code.ui.editor.api.FileEditor;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorFragment;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.settings.SettingsActivity;

public class FormatAction extends AnAction {

    public static final String ID = "formatAction";

    @Override
    public void update(@NonNull AnActionEvent event) {
        event.getPresentation().setVisible(false);
        if (!ActionPlaces.MAIN_TOOLBAR.equals(event.getPlace())) {
            return;
        }

        FileEditor fileEditor = event.getData(MainFragment.FILE_EDITOR_KEY);
        if (fileEditor == null) {
            return;
        }

        event.getPresentation().setVisible(true);
        event.getPresentation().setText(event.getDataContext().getString(R.string.menu_format));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        FileEditor fileEditor = e.getData(MainFragment.FILE_EDITOR_KEY);
        Fragment fragment = fileEditor.getFragment();
        if (fragment instanceof CodeEditorFragment) {
            ((CodeEditorFragment) fragment).format();
        }
    }
}
