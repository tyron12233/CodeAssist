package org.gradle.android.services;

import org.gradle.android.aapt2.Aapt2Runner;

public class AndroidGlobalServices {

    Aapt2Runner createAapt2Runner() {
        return new Aapt2Runner(() -> null);
    }
}
