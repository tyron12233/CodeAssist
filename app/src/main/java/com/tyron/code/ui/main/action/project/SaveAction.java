package com.tyron.code.ui.main.action.project;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.builder.project.Project;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.event.EventManager;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.fileeditor.api.FileDocumentManager;

public class SaveAction extends AnAction {

    public static final String ID = "saveAction";

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.MAIN_TOOLBAR.equals(event.getPlace())) {
            return;
        }

        Project project = event.getData(CommonDataKeys.PROJECT);
        if (project == null) {
            return;
        }

        MainViewModel mainViewModel = event.getData(MainFragment.MAIN_VIEW_MODEL_KEY);
        if (mainViewModel == null) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText("Save");
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        doSave();
    }

    public static void doSave() {
        FileDocumentManager.getInstance().saveAllContents();
        EventManager eventManager = ApplicationLoader.getInstance().getEventManager();
        eventManager.dispatchEvent(new SaveEvent());
    }
}
