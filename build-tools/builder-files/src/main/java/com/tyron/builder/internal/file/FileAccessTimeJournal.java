package com.tyron.builder.internal.file;

import java.io.File;

public interface FileAccessTimeJournal {

    long getLastAccessTime(File file);

    void setLastAccessTime(File file, long millis);

    void deleteLastAccessTime(File file);
}
