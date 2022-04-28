package com.tyron.code.ui.editor.impl.image;

import androidx.annotation.NonNull;

import com.tyron.fileeditor.api.FileEditor;
import com.tyron.fileeditor.api.FileEditorProvider;

import java.io.File;

public class ImageEditorProvider implements FileEditorProvider {

    private static final String TYPE_ID = "image-editor";

    @Override
    public boolean accept(@NonNull File file) {
        if (file.isDirectory()) {
            return false;
        }
        String name = file.getName();
        if (!name.contains(".")) {
            return false;
        }
        switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "png":
            case "jpg":
            case "jpeg":
            case "bmp":
                return true;
        }
        return false;
    }

    @NonNull
    @Override
    public FileEditor createEditor(@NonNull File file) {
        return new ImageEditor(file, this);
    }

    @NonNull
    @Override
    public String getEditorTypeId() {
        return TYPE_ID;
    }
}
