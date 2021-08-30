package com.tyron.code.file.tree.model;

import com.tyron.code.R;

import java.io.File;

import tellh.com.recyclertreeview_lib.LayoutItemType;

public class TreeFile implements LayoutItemType {

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

    @Override
    public int getLayoutId() {
        return R.layout.file_manager_item;
    }
}
