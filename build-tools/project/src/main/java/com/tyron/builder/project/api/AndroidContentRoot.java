package com.tyron.builder.project.api;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class AndroidContentRoot extends ContentRoot {

    private final List<File> javaDirectories = new ArrayList<>(4);

    public AndroidContentRoot(File rootDirectory) {
        super(rootDirectory);
    }

    public void setJavaDirectories(Collection<File> javaDirectories) {
        this.javaDirectories.clear();
        this.javaDirectories.addAll(javaDirectories);
    }

    public Collection<File> getJavaDirectories() {
        return javaDirectories;
    }

    @Override
    public Set<File> getSourceDirectories() {
        return ImmutableSet.<File>builder()
                .addAll(javaDirectories)
                .build();
    }
}
