package com.tyron.language.fileTypes;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FileTypeManager {

    private static FileTypeManager sInstance;

    public static FileTypeManager getInstance() {
        if (sInstance == null) {
            sInstance = new FileTypeManager();
        }
        return sInstance;
    }

    private final List<LanguageFileType> sRegisteredFileTypes =
            Collections.synchronizedList(new ArrayList<>());

    private FileTypeManager() {

    }

    public void registerFileType(@NotNull LanguageFileType fileType) {
        if (sRegisteredFileTypes.contains(fileType)) {
            return;
        }
        sRegisteredFileTypes.add(fileType);
    }

    @Nullable
    public LanguageFileType findFileType(@NotNull File file) {
        for (LanguageFileType fileType : sRegisteredFileTypes) {
            if (getExtension(file).equals(fileType.getDefaultExtension())) {
                return fileType;
            }
        }
        return null;
    }

    private String getExtension(@NotNull File file) {
        String name = file.getName();
        if (!name.contains(".")) {
            return "";
        }
        return name.substring(name.lastIndexOf('.') + 1, name.length());
    }
}
