package com.tyron.extension;

import org.jetbrains.annotations.NotNull;

public interface ExtensionPoint<T> {

    void registerExtension(T extension);

    T @NotNull [] getExtensions();
}
