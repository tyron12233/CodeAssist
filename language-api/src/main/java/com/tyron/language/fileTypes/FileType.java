package com.tyron.language.fileTypes;

import android.graphics.drawable.Drawable;

import org.jetbrains.annotations.NotNull;

public interface FileType {

    @NotNull String getName();

    @NotNull String getDisplayName();

    @NotNull String getDescription();

    @NotNull String getDefaultExtension();

    @NotNull Drawable getIcon();
}
