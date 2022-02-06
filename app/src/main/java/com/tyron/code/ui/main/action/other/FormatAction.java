package com.tyron.code.ui.main.action.other;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.code.R;
import com.tyron.fileeditor.api.FileEditor;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorFragment;

public class FormatAction extends AnAction {

    public static final String ID = "formatAction";

    @Override
    public void update(@NonNull AnActionEvent event) {
        event.getPresentation().setVisible(false);
        if (!ActionPlaces.MAIN_TOOLBAR.equals(event.getPlace())) {
            return;
        }

        FileEditor fileEditor = event.getData(CommonDataKeys.FILE_EDITOR_KEY);
        if (fileEditor == null) {
            return;
        }

        event.getPresentation().setVisible(true);
        event.getPresentation().setText(event.getDataContext().getString(R.string.menu_format));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        FileEditor fileEditor = e.getRequiredData(CommonDataKeys.FILE_EDITOR_KEY);
        Fragment fragment = fileEditor.getFragment();
        if (fragment instanceof CodeEditorFragment) {
            ((CodeEditorFragment) fragment).format();
        }
    }
}
