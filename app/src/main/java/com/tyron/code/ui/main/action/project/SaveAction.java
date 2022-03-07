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
import com.tyron.code.R;
import com.tyron.code.ui.editor.Savable;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.completion.progress.ProgressManager;
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
        MainViewModel viewModel = e.getRequiredData(MainFragment.MAIN_VIEW_MODEL_KEY);
        List<FileEditor> editors = viewModel.getFiles()
                .getValue();
        if (editors == null) {
            return;
        }

        Stream<FileEditor> validEditors = editors.stream()
                        .filter(it -> it.getFragment() instanceof Savable)
                        .filter(it -> ((Savable) it.getFragment()).canSave());
        List<File> filesToSave = validEditors
                .map(FileEditor::getFile)
                .collect(Collectors.toList());

        Project project = e.getRequiredData(CommonDataKeys.PROJECT);
        ProgressManager.getInstance()
                .runNonCancelableAsync(() -> {
                    List<IOException> exceptions = saveFiles(project, filesToSave);
                    if (!exceptions.isEmpty()) {
                        new MaterialAlertDialogBuilder(e.getDataContext()).setTitle(R.string.error)
                                .setPositiveButton(android.R.string.ok, null)
                                .setMessage(exceptions.stream()
                                        .map(IOException::getMessage)
                                        .collect(Collectors.joining("\n\n")))
                                .show();
                    }
                });
    }

    @WorkerThread
    private static List<IOException> saveFiles(Project project, List<File> files) {
        List<IOException> exceptions = new ArrayList<>();
        for (File file : files) {
            Module module = project.getModule(file);
            if (module == null) {
                // TODO: try to save files without a module
                continue;
            }

            FileManager fileManager = module.getFileManager();
            Optional<CharSequence> fileContent = fileManager.getFileContent(file);
            if (fileContent.isPresent()) {
                try {
                    FileUtils.writeStringToFile(file, fileContent.get()
                            .toString(), StandardCharsets.UTF_8);
                    Instant instant = Instant.ofEpochMilli(file.lastModified());

                    ProgressManager.getInstance()
                            .runLater(() -> fileManager.setLastModified(file, instant));
                } catch (IOException e) {
                    exceptions.add(e);
                }
            }
        }
        return exceptions;
    }
}
