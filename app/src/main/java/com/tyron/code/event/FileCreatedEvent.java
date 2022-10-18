package com.tyron.code.event;

import java.io.File;

/**
 * Called when a new file has been created through the file manager UI
 */
public class FileCreatedEvent extends Event {

    private final File file;

    public FileCreatedEvent(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }
}
