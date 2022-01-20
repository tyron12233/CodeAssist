package com.tyron.code.ui.main.action.project;

import android.content.Context;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.builder.project.Project;
import com.tyron.code.R;
import com.tyron.code.ui.main.IndexCallback;
import com.tyron.code.ui.main.MainFragment;

public class RefreshProjectAction extends AnAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        Context context = event.getData(CommonDataKeys.CONTEXT);
        Project project = event.getData(CommonDataKeys.PROJECT);
        IndexCallback callback = event.getData(MainFragment.INDEX_CALLBACK_KEY);
        if (!ActionPlaces.MAIN_TOOLBAR.equals(event.getPlace())
                || context == null
                || callback == null
                || project == null) {
            event.getPresentation().setVisible(false);
            return;
        }
        event.getPresentation().setVisible(true);
        event.getPresentation().setEnabled(true);
        event.getPresentation().setText(context.getString(R.string.menu_refresh));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        IndexCallback callback = e.getData(MainFragment.INDEX_CALLBACK_KEY);
        Project project = e.getData(CommonDataKeys.PROJECT);
        callback.index(project);
    }
}
