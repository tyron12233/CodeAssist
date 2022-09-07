package com.tyron.builder.gradle.internal.services;

import com.android.aaptcompiler.BlameLogger;
import com.android.aaptcompiler.ResourceCompiler;
import com.android.aaptcompiler.ResourceCompilerOptions;

import java.io.File;

public class ResourceCompilerUtils {

    public static void test(File inputFile, File outputDirectory, ResourceCompilerOptions options, BlameLogger blameLogger) {
        ResourceCompiler.compileResource(inputFile, outputDirectory, options, blameLogger);
    }
}
