package com.tyron.code.ui.main;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.tyron.builder.project.api.Project;
import com.tyron.code.ui.editor.language.LanguageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainViewModel extends ViewModel {

    /**
     * The files currently opened in the editor
     */
    private MutableLiveData<List<File>> mFiles;

    /**
     * Whether the current completion engine is indexing
     */
    private MutableLiveData<Boolean> mIndexing;

    /**
     * The text shown on the subtitle of the toolbar
     */
    private MutableLiveData<String> mCurrentState;

    private MutableLiveData<String> mToolbarTitle = new MutableLiveData<>();

    /**
     * The current position of the CodeEditor
     */
    private final MutableLiveData<Integer> currentPosition = new MutableLiveData<>(0);

    private MutableLiveData<Integer> mBottomSheetState =
            new MutableLiveData<>(BottomSheetBehavior.STATE_COLLAPSED);

    public MutableLiveData<String> getCurrentState() {
        if (mCurrentState == null) {
            mCurrentState = new MutableLiveData<>(null);
        }
        return mCurrentState;
    }


    public void setCurrentState(@Nullable String message) {
        mCurrentState.setValue(message);
    }

    public LiveData<String> getToolbarTitle() {
        return mToolbarTitle;
    }

    public void setToolbarTitle(String title) {
        mToolbarTitle.setValue(title);
    }

    public LiveData<Integer> getBottomSheetState() {
        return mBottomSheetState;
    }


    public void setBottomSheetState(@BottomSheetBehavior.State int bottomSheetState) {
        mBottomSheetState.setValue(bottomSheetState);
    }

    public MutableLiveData<Boolean> isIndexing() {
        if (mIndexing == null) {
            mIndexing = new MutableLiveData<>(false);
        }
        return mIndexing;
    }

    public void setIndexing(boolean indexing) {
        mIndexing.setValue(indexing);
    }

    public LiveData<List<File>> getFiles() {
        if (mFiles == null) {
            mFiles = new MutableLiveData<>(new ArrayList<>());
        }
        return mFiles;
    }

    public void setFiles(@NonNull List<File> files) {
        if (mFiles == null) {
            mFiles = new MutableLiveData<>(new ArrayList<>());
        }
        mFiles.setValue(files);
    }

    public LiveData<Integer> getCurrentPosition() {
        return currentPosition;
    }

    public void updateCurrentPosition(int pos) {
        if (pos == currentPosition.getValue()) {
            return;
        }
        currentPosition.setValue(pos);
    }

    public File getCurrentFile() {
        List<File> files = getFiles().getValue();
        if (files == null) {
            return null;
        }

        Integer currentPos = currentPosition.getValue();
        if (currentPos == null) {
            return null;
        }

        if (files.size() - 1 < currentPos) {
            return null;
        }

        return files.get(currentPos);
    }

    public void clear() {
        mFiles.setValue(new ArrayList<>());
    }


    /**
     * Opens this file to the editor
     * @param file The fle to be opened
     * @return whether the operation was successful
     */
    public boolean openFile(File file) {
        if (!LanguageManager.getInstance().supports(file)) {
            return false;
        }

        if (!file.exists()) {
            return false;
        }

        int index = getFiles().getValue().indexOf(file);
        if (index >= 0) {
            updateCurrentPosition(index);
            return true;
        }

        addFile(file);
        return true;
    }

    public void addFile(File file) {
        List<File> files = getFiles().getValue();
        if (files == null) {
            files = new ArrayList<>();
        }
        files.add(file);
        mFiles.setValue(files);
        updateCurrentPosition(files.indexOf(file));
    }

    public void removeFile(File file) {
        List<File> files = getFiles().getValue();
        if (files == null) {
            return;
        }
        files.remove(file);
        mFiles.setValue(files);
    }

    public void initializeProject(Project project) {
        setIndexing(true);
    }
}
