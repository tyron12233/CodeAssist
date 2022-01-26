package com.tyron.kotlin_completion.action;

import com.tyron.kotlin_completion.CompiledFile;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;

public class CommonKotlinKeys {

    public static final Key<CompiledFile> COMPILED_FILE = Key.create("Compiled File");
}
