package com.tyron.code.sdk;

import androidx.annotation.VisibleForTesting;

import com.google.common.collect.ImmutableList;
import com.tyron.completion.java.CompletionModule;

import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.kotlin.com.intellij.sdk.Sdk;
import org.jetbrains.kotlin.com.intellij.sdk.SdkManager;

import java.io.File;
import java.io.IOException;

public class SdkManagerImpl extends SdkManager {

    private Sdk defaultSdk;

    private final Project project;

    public SdkManagerImpl(Project project) {
        this.project = project;
    }

    @Override
    public Sdk getDefaultSdk() {
        return defaultSdk;
    }

    @Override
    public void loadDefaultSdk() throws IOException {
        if (defaultSdk != null) {
            return;
        }
        File androidJar = CompletionModule.getAndroidJar();
        File lambdaStubs = CompletionModule.getLambdaStubs();

        assert androidJar != null;
        assert lambdaStubs != null;

        File filesDir = CompletionModule.getContext().getExternalFilesDir(null);
        File defaultSdkDir = new File(filesDir, "defaultSdk");
        if (!defaultSdkDir.exists() && !defaultSdkDir.mkdir()) {
            throw new IOException("Failed to create defaultSdk directory");
        }

        File defaultLambdaStubs = new File(defaultSdkDir, "core-lambda-stubs.jar");
        if (!defaultLambdaStubs.exists()) {
            FileUtil.copy(lambdaStubs, defaultLambdaStubs);
        }

        File defaultSdkJar = new File(defaultSdkDir, "rt.jar");
        if (!defaultSdkJar.exists()) {
            FileUtil.copy(androidJar, defaultSdkJar);
        }

        defaultSdk = new Sdk("defaultSdk",
                project,
                defaultSdkDir.getPath(),
                ImmutableList.of(defaultSdkJar, defaultLambdaStubs));
    }

    @VisibleForTesting
    public void setDefaultSdk(Sdk sdk) {
        defaultSdk = sdk;
    }
}
