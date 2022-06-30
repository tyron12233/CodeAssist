package com.tyron.builder.android.services;

import com.tyron.builder.android.aapt2.Aapt2Runner;

public class AndroidGlobalServices {

    Aapt2Runner createAapt2Runner() {
        return new Aapt2Runner(() -> null);
    }
}
