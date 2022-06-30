package com.tyron.code.ui.main.action.project;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.code.event.EventManager;
import com.tyron.code.ui.editor.Savable;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.fileeditor.api.FileDocumentManager;
import com.tyron.fileeditor.api.FileEditor;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
