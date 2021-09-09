package com.tyron.code.ui.main;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainViewModel extends ViewModel {

    private MutableLiveData<List<File>> mFiles;

    public final MutableLiveData<Integer> currentPosition = new MutableLiveData<>(0);

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
