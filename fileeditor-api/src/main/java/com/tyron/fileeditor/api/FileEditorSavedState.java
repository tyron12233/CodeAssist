package com.tyron.fileeditor.api;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;
import com.tyron.fileeditor.api.FileEditor;

import java.io.File;

@Keep
public class FileEditorSavedState {

    @SerializedName("name")
    private final String mName;

    @SerializedName("file")
    private final File mFile;

    public FileEditorSavedState(FileEditor editor) {
        this(editor.getName(), editor.getFile());
    }

    public FileEditorSavedState(String mName, File mFile) {
        this.mName = mName;
        this.mFile = mFile;
    }

    public String getName() {
        return mName;
    }

    public File getFile() {
        return mFile;
    }
}
