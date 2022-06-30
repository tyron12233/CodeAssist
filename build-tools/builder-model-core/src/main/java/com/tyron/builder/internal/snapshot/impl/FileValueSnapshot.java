package com.tyron.builder.internal.snapshot.impl;

import com.google.common.hash.Hasher;
import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.ValueSnapshotter;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class FileValueSnapshot extends AbstractScalarValueSnapshot<String> implements Isolatable<File> {
    public FileValueSnapshot(File value) {
        super(value.getPath());
    }

    public FileValueSnapshot(String value) {
        super(value);
    }

    @Override
    public ValueSnapshot asSnapshot() {
        return this;
    }

    @Override
    public File isolate() {
        return new File(getValue());
    }

    @Nullable
    @Override
    public <S> S coerce(Class<S> type) {
        if (type.isAssignableFrom(File.class)) {
            return type.cast(isolate());
        }
        return null;
    }

    @Override
    public ValueSnapshot snapshot(Object value, ValueSnapshotter snapshotter) {
        if (value instanceof File) {
            File file = (File) value;
            if (file.getPath().equals(getValue())) {
                return this;
            }
        }
        return snapshotter.snapshot(value);
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putString(getValue(), StandardCharsets.UTF_8);
    }
}