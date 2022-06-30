package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.api.Describable;

import java.io.File;

public class InstallationLocation implements Describable {

    private File location;

    private String source;

    public InstallationLocation(File location, String source) {
        this.location = location;
        this.source = source;
    }

    public File getLocation() {
        return location;
    }

    @Override
    public String getDisplayName() {
        return "'" + location.getAbsolutePath() + "' (" + source + ")";
    }

    public String getSource() {
        return source;
    }

}
