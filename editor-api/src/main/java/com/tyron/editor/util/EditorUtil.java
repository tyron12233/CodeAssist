package com.tyron.editor.util;

import com.tyron.common.SharedPreferenceKeys;
import com.tyron.common.util.UniqueNameBuilder;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.File;
import java.util.List;
import java.util.Objects;

public class EditorUtil {

    public static String getUniqueTabTitle(@NonNull File currentFile, List<File> files) {
        int sameFileNameCount = 0;
        UniqueNameBuilder<File> builder = new UniqueNameBuilder<>("", "/");

        for (File openFile : files) {
            if (openFile.getName().equals(currentFile.getName())) {
                sameFileNameCount++;
            }
            builder.addPath(openFile, openFile.getPath());
        }
        if (sameFileNameCount > 1) {
            return builder.getShortPath(currentFile);
        } else {
            return currentFile.getName();
        }
    }
}
