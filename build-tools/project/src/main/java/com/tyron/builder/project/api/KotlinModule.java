package com.tyron.builder.project.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Map;

public interface KotlinModule extends Module {

    @NonNull
    Map<String, File> getKotlinFiles();

    @NonNull
    File getKotlinDirectory();

    @Nullable
    File getKotlinFile(String packageName);

    void addKotlinFile(File file);
}
