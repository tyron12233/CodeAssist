package com.tyron.builder.plugin.builder;

import org.jetbrains.annotations.NotNull;

import java.io.File;

public interface ManifestParser {
    String getPackage(@NotNull File manifestFile);
}