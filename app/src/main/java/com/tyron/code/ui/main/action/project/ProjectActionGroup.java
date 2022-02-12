package com.tyron.code.ui.main.action.project;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.tyron.actions.ActionGroup;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.code.R;

public class ProjectActionGroup extends ActionGroup {

    public static final String ID = "projectActionGroup";

    @Override
    public void update(@NonNull AnActionEvent event) {
        if (!ActionPlaces.MAIN_TOOLBAR.equals(event.getPlace())) {
            event.getPresentation()
                    .setVisible(false);
            return;
        }

        Context context = event.getData(CommonDataKeys.CONTEXT);
        if (context == null) {
            event.getPresentation()
                    .setVisible(false);
            return;
        }

        event.getPresentation()
                .setVisible(true);
        event.getPresentation()
                .setEnabled(true);
        event.getPresentation()
                .setText(context.getString(R.string.item_project));
        event.getPresentation()
                .setIcon(ContextCompat.getDrawable(context, R.drawable.round_folder_24));
    }

    @Override
    public boolean isPopup() {
        return true;
    }

    @Override
    public AnAction[] getChildren(@Nullable AnActionEvent e) {
        return new AnAction[]{new SaveAction(), new RefreshProjectAction(),
                new OpenLibraryManagerAction()};
    }
}
