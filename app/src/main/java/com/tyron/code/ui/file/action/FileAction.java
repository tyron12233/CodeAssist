package com.tyron.code.ui.file.action;

import android.content.Context;
import android.view.Menu;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;

import java.io.File;

public abstract class FileAction extends AnAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.FILE_MANAGER.equals(event.getPlace())) {
            return;
        }

        File file = event.getData(CommonDataKeys.FILE);
        if (file == null) {
            return;
        }

        if (!isApplicable(file)) {
            return;
        }

        Fragment fragment = event.getData(CommonDataKeys.FRAGMENT);
        if (!(fragment instanceof TreeFileManagerFragment)) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText(getTitle(event.getDataContext()));
    }

    public abstract String getTitle(Context context);

    public abstract boolean isApplicable(File file);
}
