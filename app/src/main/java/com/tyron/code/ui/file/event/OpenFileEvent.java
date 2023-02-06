package com.tyron.code.ui.file.event;

import com.tyron.code.event.Event;

import java.io.File;

public class OpenFileEvent extends Event {
    private final File file;

    public OpenFileEvent(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }
}
