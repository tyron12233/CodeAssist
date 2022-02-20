package com.tyron.code.service;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tyron.builder.model.ProjectSettings;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.fileeditor.api.FileEditor;
import com.tyron.fileeditor.api.FileEditorSavedState;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.log.LogViewModel;
import com.tyron.builder.project.Project;
import com.tyron.code.ui.main.MainViewModel;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles the communication between the Index service and the main fragment
 */
public class IndexServiceConnection implements ServiceConnection {

    private final MainViewModel mMainViewModel;
    private final LogViewModel mLogViewModel;
    private final ILogger mLogger;
    private Project mProject;

    public IndexServiceConnection(MainViewModel mainViewModel, LogViewModel logViewModel) {
        mMainViewModel = mainViewModel;
        mLogViewModel = logViewModel;
        mLogger = ILogger.wrap(logViewModel);
    }

    public void setProject(Project project) {
        mProject = project;
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        IndexService.IndexBinder binder = (IndexService.IndexBinder) iBinder;
        try {
            mProject.setCompiling(true);
            binder.index(mProject, new TaskListener(), mLogger);
        } finally {
            mProject.setCompiling(false);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        mMainViewModel.setIndexing(false);
        mMainViewModel.setCurrentState(null);
    }

    private class TaskListener implements ProjectManager.TaskListener {

        @Override
        public void onTaskStarted(String message) {
            mMainViewModel.setCurrentState(message);
        }

        @SuppressWarnings("ConstantConditions")
        @Override
        public void onComplete(Project project, boolean success, String message) {
            mMainViewModel.setIndexing(false);
            mMainViewModel.setCurrentState(null);
            if (success) {
                Project currentProject = ProjectManager.getInstance()
                        .getCurrentProject();
                if (project.equals(currentProject)) {
                    mMainViewModel.setToolbarTitle(project.getRootFile()
                                                           .getName());
                }
            } else {
                if (mMainViewModel.getBottomSheetState()
                            .getValue() != BottomSheetBehavior.STATE_EXPANDED) {
                    mMainViewModel.setBottomSheetState(BottomSheetBehavior.STATE_HALF_EXPANDED);
                }
                mLogViewModel.e(LogViewModel.BUILD_LOG, message);
            }
        }
    }

    public static List<FileEditor> getOpenedFiles(ProjectSettings settings) {
        String openedFilesString = settings.getString(ProjectSettings.SAVED_EDITOR_FILES, null);
        if (openedFilesString != null) {
            try {
                Type type = new TypeToken<List<FileEditorSavedState>>() {
                }.getType();
                List<FileEditorSavedState> savedStates =
                        new Gson().fromJson(openedFilesString, type);
                return savedStates.stream()
                        .filter(it -> it.getFile()
                                .exists())
                        .map(FileEditorManagerImpl.getInstance()::openFile)
                        .collect(Collectors.toList());
            } catch (Throwable e) {
                // ignored, users may have edited the file manually and is corrupt
                // just return an empty editor list
            }
        }
        return new ArrayList<>();
    }

    public static void restoreFileEditors(Project currentProject, MainViewModel viewModel) {
        List<FileEditor> openedFiles = getOpenedFiles(currentProject.getSettings());

        List<FileEditor> value = viewModel.getFiles()
                .getValue();
        if (value != null) {
            List<File> toClose = value.stream()
                    .map(FileEditor::getFile)
                    .filter(file -> openedFiles.stream()
                            .noneMatch(editor -> file.equals(editor.getFile())))
                    .collect(Collectors.toList());
            toClose.forEach(FileEditorManagerImpl.getInstance()::closeFile);
        }
        viewModel.setFiles(openedFiles);
    }
}
