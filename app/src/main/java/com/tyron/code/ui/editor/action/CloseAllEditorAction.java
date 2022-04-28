package com.tyron.code.ui.editor.action;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.code.R;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.main.MainViewModel;

public class CloseAllEditorAction extends AnAction {

    public static final String ID = "editorTabCloseAll";

    @Override
    public void update(@NonNull AnActionEvent event) {
        MainViewModel mainViewModel = event.getData(MainFragment.MAIN_VIEW_MODEL_KEY);

        event.getPresentation().setVisible(false);
        if (!ActionPlaces.EDITOR_TAB.equals(event.getPlace())) {
            return;
        }
        if (mainViewModel == null) {
            return;
        }

        event.getPresentation().setVisible(true);
        event.getPresentation().setText(event.getDataContext().getString(R.string.menu_close_all));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        MainViewModel mainViewModel = e.getData(MainFragment.MAIN_VIEW_MODEL_KEY);
        mainViewModel.clear();
    }
}
