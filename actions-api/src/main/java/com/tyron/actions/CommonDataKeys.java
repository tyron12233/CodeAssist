package com.tyron.actions;

import android.content.Context;

import androidx.fragment.app.Fragment;

import com.tyron.builder.project.Project;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;

import java.io.File;

public class CommonDataKeys {

    /**
     * The current file opened in the editor
     */
    public static final Key<File> FILE = Key.create("file");

    /**
     * The current accessible context
     */
    public static final Key<Context> CONTEXT = Key.create("context");

    /**
     * The current fragment this action is invoked on
     */
    public static final Key<Fragment> FRAGMENT = Key.create("fragment");

    /**
     * The current opened project
     */
    public static final Key<Project> PROJECT = Key.create("project");
}
