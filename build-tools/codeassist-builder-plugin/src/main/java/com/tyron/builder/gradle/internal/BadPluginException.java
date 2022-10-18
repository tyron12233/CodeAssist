package com.tyron.builder.gradle.internal;

import com.android.annotations.NonNull;
import org.gradle.api.GradleException;

public class BadPluginException extends GradleException {

    public BadPluginException(@NonNull String message) {
        super(message);
    }
}