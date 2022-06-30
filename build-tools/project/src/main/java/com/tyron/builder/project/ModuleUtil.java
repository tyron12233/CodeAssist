package com.tyron.builder.project;

import androidx.annotation.Nullable;

import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.impl.AndroidModuleImpl;
import com.tyron.builder.project.impl.JavaModuleImpl;
import com.tyron.builder.project.impl.ModuleImpl;

import java.io.File;

public class ModuleUtil {

    @Nullable
    public static Module fromDirectory(File directory) {
        File moduleSettings = new File(directory, "app_config.json");
        if (!moduleSettings.exists()) {
            return null;
        }
        ModuleSettings settings = new ModuleSettings(moduleSettings);
        String type = settings.getString(ModuleSettings.MODULE_TYPE, "android_app");
        if (type == null) {
            type = "android_app";
        }

        switch (type) {
            case "library":
                return new JavaModuleImpl(directory);
            default:
            case "android_app":
                return new AndroidModuleImpl(directory);
        }
    }
}
