package com.tyron.builder.internal.jar;

import androidx.annotation.NonNull;

import java.util.jar.Attributes;

public interface JarOptions {

    @NonNull
    Attributes getAttributes();

    void setAttributes(Attributes attributes);
}
