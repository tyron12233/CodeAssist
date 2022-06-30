package com.tyron.code.ui.main.action.debug;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.actions.ActionGroup;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.Presentation;
import com.tyron.code.BuildConfig;

public class DebugActionGroup extends ActionGroup {

    public static final String ID = "debugActionGroup";

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!BuildConfig.DEBUG) {
            return;
        }

        if (!ActionPlaces.MAIN_TOOLBAR.equals(event.getPlace())) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText("Debug");
    }

    @Override
    public AnAction[] getChildren(@Nullable AnActionEvent e) {
        return new AnAction[]{new LoadActionJarAction(), new LoadFileEditorProviderAction(),
                new CrashAction(), new LoadXmlRepositoryAction(), new RunLongRunningTaskAction()};
    }
}
