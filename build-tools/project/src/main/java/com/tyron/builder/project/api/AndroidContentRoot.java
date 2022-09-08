package com.tyron.builder.project.api;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
}
