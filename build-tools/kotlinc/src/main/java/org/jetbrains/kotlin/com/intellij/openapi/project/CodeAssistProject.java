package org.jetbrains.kotlin.com.intellij.openapi.project;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.mock.MockProject;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.org.picocontainer.PicoContainer;

public class CodeAssistProject extends MockProject {

    private VirtualFile projectRoot;

    public CodeAssistProject(@Nullable PicoContainer parent,
                             @NonNull Disposable parentDisposable) {
        super(parent, parentDisposable);
    }

    public void setProjectRoot(VirtualFile projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public VirtualFile getProjectFile() {
        return projectRoot;
    }

    @Nullable
    @Override
    public String getProjectFilePath() {
        return projectRoot.getPath();
    }
}
