package com.tyron.code.ui.file.tree.model;

import android.graphics.drawable.Drawable;

import java.io.File;

public class TreeFile {

    public static TreeFile fromFile(File file) {
        return new TreeFile(file);
    }

    private final File mFile;

    public TreeFile(File file) {
        mFile = file;
    }

    public File getFile() {
        return mFile;
    }

    public Drawable getIcon() {
        return null;
    }
}
