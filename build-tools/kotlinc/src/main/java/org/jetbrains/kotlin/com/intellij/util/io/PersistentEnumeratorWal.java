package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

public class PersistentEnumeratorWal<Data> {
    public PersistentEnumeratorWal(KeyDescriptor<Data> dataDescriptor,
                                          boolean b,
                                          Path path,
                                          @NonNull ExecutorService executorService,
                                          boolean b1) {
    }

    public void enumerate(Data value, int newValueId) {

    }

    public void flush() {

    }

    public void close() {

    }
}
