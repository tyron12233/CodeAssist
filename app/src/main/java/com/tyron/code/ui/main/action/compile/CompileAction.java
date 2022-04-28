package com.tyron.code.ui.main.action.compile;

import android.content.Context;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.builder.compiler.BuildType;
import com.tyron.code.ui.main.CompileCallback;
import com.tyron.code.ui.main.MainFragment;

public abstract class CompileAction extends AnAction {

    protected final BuildType mBuildType;

    public CompileAction(BuildType type) {
        mBuildType = type;
    }

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        Context context = event.getData(CommonDataKeys.CONTEXT);

        if (!ActionPlaces.MAIN_TOOLBAR.equals(event.getPlace()) || context == null) {
            presentation.setVisible(false);
            return;
        }

        CompileCallback data = event.getData(MainFragment.COMPILE_CALLBACK_KEY);
        if (data == null) {
            event.getPresentation().setVisible(false);
            return;
        }


        presentation.setText(getTitle(context));
        presentation.setEnabled(true);
        presentation.setVisible(true);
    }

    public abstract String getTitle(Context context);
}
