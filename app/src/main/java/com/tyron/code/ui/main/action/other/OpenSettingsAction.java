package com.tyron.code.ui.main.action.other;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.code.R;
import com.tyron.code.ui.settings.SettingsActivity;

public class OpenSettingsAction extends AnAction {

    public static final String ID = "openSettingsAction";

    @Override
    public void update(@NonNull AnActionEvent event) {
        event.getPresentation().setVisible(false);
        if (!ActionPlaces.MAIN_TOOLBAR.equals(event.getPlace())) {
            return;
        }

        Activity activity = event.getData(CommonDataKeys.ACTIVITY);
        if (activity == null) {
            return;
        }

        event.getPresentation().setVisible(true);
        event.getPresentation().setText(event.getDataContext().getString(R.string.menu_settings));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Intent intent = new Intent();
        intent.setClass(e.getDataContext(), SettingsActivity.class);
        e.getDataContext().startActivity(intent);
    }
}
