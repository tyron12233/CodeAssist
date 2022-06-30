package com.tyron.builder.android.aapt2;

import java.io.File;
import java.io.IOException;

/**
 * Interface for providing the binary file for the AAPT2 Tool.
 */
public interface Aapt2BinaryProvider {
    File getBinary() throws IOException;
}
