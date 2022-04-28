package com.tyron.code.ui.file.tree.model;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.appcompat.content.res.AppCompatResources;

import com.tyron.code.R;

import java.io.File;

public class TreeJavaFile extends TreeFile {

    public TreeJavaFile(File file) {
        super(file);
    }

    @Override
    public Drawable getIcon(Context context) {
        return super.getIcon(context);
    }
}
