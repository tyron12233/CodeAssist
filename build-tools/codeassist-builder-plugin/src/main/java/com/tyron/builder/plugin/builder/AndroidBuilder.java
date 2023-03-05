package com.tyron.builder.plugin.builder;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

public class AndroidBuilder {

    public void convertBytecode(
            @NotNull Iterable<String> classesLocation,
            @NotNull Iterable<String> libraries,
            @NotNull String outDexFile,
            @NotNull DexOptions dexOptions) throws IOException, InterruptedException {

    }
}
