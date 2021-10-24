package com.tyron.code.ui.main;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

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

    /**
     * The current position of the CodeEditor
     */
    public final MutableLiveData<Integer> currentPosition = new MutableLiveData<>(0);

    public MutableLiveData<String> getCurrentState() {
        if (mCurrentState == null) {
            mCurrentState = new MutableLiveData<>(null);
        }
        return mCurrentState;
    }

    public void setCurrentState(@Nullable String message) {
        mCurrentState.setValue(message);
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

    public void setFiles(@NonNull List<File> files) {
        if (mFiles == null) {
            mFiles = new MutableLiveData<>(new ArrayList<>());
        }
        mFiles.setValue(files);
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
}
