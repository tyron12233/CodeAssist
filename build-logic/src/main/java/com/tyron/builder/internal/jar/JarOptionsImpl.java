package com.tyron.builder.internal.jar;

import androidx.annotation.NonNull;

import java.util.jar.Attributes;

public class JarOptionsImpl implements JarOptions {

    private Attributes attributes;

    public JarOptionsImpl(Attributes attributes) {
        this.attributes = attributes;
    }

    @NonNull
    @Override
    public Attributes getAttributes() {
        return attributes;
    }

    @Override
    public void setAttributes(Attributes attributes) {
        this.attributes = attributes;
    }
}
