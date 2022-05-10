package com.tyron.editor.event;

import org.jetbrains.annotations.NotNull;

import java.util.EventListener;

public interface ContentListener extends EventListener {
    ContentListener[] EMPTY_ARRAY = new ContentListener[0];

    default void beforeContentChanged(@NotNull ContentEvent event) {

    }

    default void contentChanged(@NotNull ContentEvent event) {

    }
}
