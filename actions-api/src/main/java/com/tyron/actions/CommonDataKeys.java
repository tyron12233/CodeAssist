package com.tyron.actions;

import android.content.Context;

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
}
