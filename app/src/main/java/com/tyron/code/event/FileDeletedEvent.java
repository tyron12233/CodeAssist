package com.tyron.code.event;

import java.io.File;

public class FileDeletedEvent extends Event {


    private final File deletedFile;

    public FileDeletedEvent(File deletedFile) {
        this.deletedFile = deletedFile;
    }

    public File getDeletedFile() {
        return deletedFile;
    }
}
