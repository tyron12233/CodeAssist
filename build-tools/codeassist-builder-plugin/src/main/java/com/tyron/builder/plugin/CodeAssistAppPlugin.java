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

        // This is a hack to change the java.runtime.version
        // this is to make annotation processors believe we are using jdk 11
        unchecked(() -> {
            Field defaultsField = Properties.class.getDeclaredField("defaults");
            defaultsField.setAccessible(true);
            Properties defaultProps = (Properties) defaultsField.get(System.getProperties());
            defaultProps.setProperty("java.runtime.version", "11.0.0");
        });

        System.setProperty("ANDROID_USER_HOME", "/data/data/com.tyron.code/files/ANDROID_HOME");
        project.getPlugins().apply(AppPlugin.class);
    }
}