package com.tyron.code.ui.file.action;

import android.content.Context;
import android.view.Menu;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;

import java.io.File;

public abstract class FileAction extends AnAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
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

        event.getPresentation().setVisible(true);
        event.getPresentation().setText(getTitle(event.getDataContext()));
    }

    public abstract String getTitle(Context context);

    public abstract boolean isApplicable(File file);
}
