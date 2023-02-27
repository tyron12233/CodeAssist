package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;

public interface RepresentableAsByteArraySequence {
    @NonNull
    ByteArraySequence asByteArraySequence();
}
