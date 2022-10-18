package com.tyron.builder.plugin;


import static org.gradle.util.GUtil.unchecked;

import com.tyron.builder.gradle.internal.plugins.AppPlugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.Properties;

public class CodeAssistAppPlugin implements Plugin<Project> {

    @Override
    public void apply(@NotNull Project project) {
    }
}