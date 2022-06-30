package com.tyron.code.ui.main.action.debug;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.Presentation;

/**
 * Used to test whether files are saved when the app crashes.
 */
public class CrashAction extends AnAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.MAIN_TOOLBAR.equals(event.getPlace())) {
            return;
        }

        presentation.setText("Throw uncaught exception");
        presentation.setVisible(true);
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        throw new RuntimeException("Application manually crashed.");
    }
}
