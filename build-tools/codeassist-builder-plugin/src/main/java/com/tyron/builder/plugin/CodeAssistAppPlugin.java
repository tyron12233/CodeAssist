package com.tyron.builder.plugin;

import com.tyron.builder.gradle.internal.plugins.AppPlugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.jetbrains.annotations.NotNull;

public class CodeAssistAppPlugin implements Plugin<Project> {

    @Override
    public void apply(@NotNull Project project) {
        System.setProperty("ANDROID_USER_HOME", "/data/data/com.tyron.code/files/ANDROID_HOME");
        project.getPlugins().apply(AppPlugin.class);
    }
}