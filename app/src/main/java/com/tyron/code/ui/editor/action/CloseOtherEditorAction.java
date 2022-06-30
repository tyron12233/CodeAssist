package com.tyron.code.ui.editor.action;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.code.R;
import com.tyron.fileeditor.api.FileEditor;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.main.MainViewModel;

public class CloseOtherEditorAction extends AnAction {

    public static final String ID = "editorTabCloseOthers";

    @Override
    public void update(@NonNull AnActionEvent event) {
        event.getPresentation().setVisible(false);
        if (!ActionPlaces.EDITOR_TAB.equals(event.getPlace())) {
            return;
        }

        MainViewModel mainViewModel = event.getData(MainFragment.MAIN_VIEW_MODEL_KEY);
        if (mainViewModel == null) {
            return;
        }
        FileEditor fileEditor = mainViewModel.getCurrentFileEditor();
        if (fileEditor == null) {
            return;
        }

        event.getPresentation().setVisible(true);
        event.getPresentation().setText(event.getDataContext().getString(R.string.menu_close_others));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        MainViewModel mainViewModel = e.getRequiredData(MainFragment.MAIN_VIEW_MODEL_KEY);
        FileEditor fileEditor = e.getRequiredData(CommonDataKeys.FILE_EDITOR_KEY);
        mainViewModel.removeOthers(fileEditor.getFile());
    }
}
