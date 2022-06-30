package com.tyron.builder.android.aapt2.impl;

import com.tyron.builder.android.aapt2.Aapt2BinaryProvider;

import java.io.File;
import java.io.IOException;

public class DefaultAapt2BinaryProvider implements Aapt2BinaryProvider {

    private final File binary;

    public DefaultAapt2BinaryProvider(File binary) {
        this.binary = binary;
    }

    @Override
    public File getBinary() throws IOException {
        return binary;
    }
}
