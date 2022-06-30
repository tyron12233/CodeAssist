package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.common.collect.ImmutableSet;
import com.tyron.fileeditor.api.FileEditor;
import com.tyron.fileeditor.api.FileEditorProvider;

import java.io.File;
import java.util.Set;

import kotlin.io.FilesKt;

public class RosemoeEditorProvider implements FileEditorProvider {

    private static final Set<String> NON_TEXT_FILES = ImmutableSet.<String>builder()
            .add("jar", "zip", "png", "jpg")
            .add("jpeg", "mp4", "mp3", "ogg")
            .add("7zip", "tar")
            .build();
    private static final String TYPE_ID = "rosemoe-code-editor";

    @Override
    public boolean accept(@NonNull File file) {
        boolean nonText = NON_TEXT_FILES.stream()
                .anyMatch(it -> FilesKt.getExtension(file).endsWith(it));
        if (nonText) {
            return false;
        }
        return file.exists() && !file.isDirectory();
    }

    @NonNull
    @Override
    public FileEditor createEditor(@NonNull Context context, @NonNull File file) {
        return new RosemoeCodeEditor(context, file, this);
    }

    @NonNull
    @Override
    public String getEditorTypeId() {
        return TYPE_ID;
    }
}
