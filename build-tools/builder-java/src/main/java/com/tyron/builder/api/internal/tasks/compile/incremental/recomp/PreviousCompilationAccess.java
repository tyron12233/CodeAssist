package com.tyron.builder.api.internal.tasks.compile.incremental.recomp;


import com.tyron.builder.internal.serialize.kryo.KryoBackedDecoder;
import com.tyron.builder.internal.serialize.kryo.KryoBackedEncoder;
import com.tyron.builder.api.internal.cache.StringInterner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class PreviousCompilationAccess {

    private final StringInterner interner;

    public PreviousCompilationAccess(StringInterner interner) {
        this.interner = interner;
    }

    public PreviousCompilationData readPreviousCompilationData(File source) {
        try (KryoBackedDecoder encoder = new KryoBackedDecoder(new FileInputStream(source))) {
            return new PreviousCompilationData.Serializer(interner).read(encoder);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read previous compilation result.", e);
        }
    }

    public void writePreviousCompilationData(PreviousCompilationData data, File target) {
        try (KryoBackedEncoder encoder = new KryoBackedEncoder(new FileOutputStream(target))) {
            new PreviousCompilationData.Serializer(interner).write(encoder, data);
        } catch (Exception e) {
            throw new IllegalStateException("Could not store compilation result", e);
        }
    }
}

