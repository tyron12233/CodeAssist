package com.tyron.code.ui.file.tree.model;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.tyron.code.R;

import java.io.File;

public class TreeFile {

    @Nullable
    public static TreeFile fromFile(File file) {
        if (file == null) {
            return null;
        }
        if (file.isDirectory()) {
            return new TreeFolder(file);
        }
        if (file.getName().endsWith(".java")) {
            return new TreeJavaFile(file);
        }
        return new TreeFile(file);
    }

    private final File mFile;

    public TreeFile(File file) {
        mFile = file;
    }

    public File getFile() {
        return mFile;
    }

    public Drawable getIcon(Context context) {
        return AppCompatResources.getDrawable(context,
                R.drawable.round_insert_drive_file_24);
    }
}
