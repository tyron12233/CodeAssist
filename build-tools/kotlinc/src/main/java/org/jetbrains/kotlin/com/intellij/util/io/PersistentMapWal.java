package org.jetbrains.kotlin.com.intellij.util.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

public class PersistentMapWal<Key, Value> {
    public PersistentMapWal(KeyDescriptor<Key> keyDescriptor,
                            DataExternalizer<Value> valueExternalizer,
                            boolean useCompression,
                            Path walFile,
                            ExecutorService walExecutor,
                            boolean b) {

    }

    public void closeAndDelete() throws IOException {

    }

    public void put(Key key, Value value) {

    }

    public void appendData(Key key, AppendablePersistentMap.ValueDataAppender appender) {

    }

    public void remove(Key key) {

    }

    public void flush() {

    }

    public void close() {

    }
}
