package org.jetbrains.kotlin.com.intellij.sdk;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;

import java.io.IOException;

public abstract class SdkManager {

    public static SdkManager getInstance(Project project) {
        return project.getService(SdkManager.class);
    }

    public abstract Sdk getDefaultSdk();

    public abstract void loadDefaultSdk() throws IOException;
}
