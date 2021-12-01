package com.tyron.builder.project.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Map;

public interface KotlinProject extends Project {

    @NonNull
    Map<String, File> getKotlinFiles();

    @Nullable
    File getKotlinFile(String packageName);

    void addKotlinFile(File file);
}
