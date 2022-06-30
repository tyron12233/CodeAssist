package com.tyron.code.ui.main.action.debug;

import android.app.Activity;
import android.app.ProgressDialog;

import androidx.annotation.NonNull;

import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.completion.progress.ProgressManager;

/**
 * Runs a task in the background for five seconds. A progress dialog  should appear after 2 seconds.
 */
public class RunLongRunningTaskAction extends AnAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(true);
        presentation.setText("Run long running task");
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Activity activity = e.getRequiredData(CommonDataKeys.ACTIVITY);

        ProgressDialog dialog = new ProgressDialog(activity);
        Runnable task = () -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException interruptedException) {
                interruptedException.printStackTrace();
            }
        };
        Runnable longRunningRunnable = dialog::show;
        Runnable cancelRunnable = dialog::dismiss;

        ProgressManager.getInstance().runNonCancelableAsync(task, longRunningRunnable, cancelRunnable);
    }
}
