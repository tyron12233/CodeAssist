package com.tyron.builder.tooling.internal.provider.serialization;

import com.tyron.builder.internal.classloader.ClassLoaderSpec;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ClassLoaderDetails implements Serializable {
    // TODO:ADAM - using a UUID means we create a ClassLoader hierarchy for each daemon process we talk to. Instead, use the spec to decide whether to reuse a ClassLoader
    final UUID uuid;
    final ClassLoaderSpec spec;
    final List<ClassLoaderDetails> parents = new ArrayList<ClassLoaderDetails>();

    public ClassLoaderDetails(UUID uuid, ClassLoaderSpec spec) {
        this.uuid = uuid;
        this.spec = spec;
    }

    @Override
    public String toString() {
        return "{" + getClass().getSimpleName() + " uuid: " + uuid + " spec: " + spec + " parents: " + parents + "}";
    }
}
