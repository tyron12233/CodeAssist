package com.tyron.builder.api.internal.file.delete;

import com.tyron.builder.api.NonExtensible;

@NonExtensible
public class DefaultDeleteSpec implements DeleteSpecInternal {
    private Object[] paths;
    private boolean followSymlinks;

    public DefaultDeleteSpec() {
        paths = new Object[0];
        followSymlinks = false;
    }

    @Override
    public Object[] getPaths() {
        return paths;
    }

    @Override
    public DefaultDeleteSpec delete(Object... files) {
        paths = files;
        return this;
    }

    @Override
    public void setFollowSymlinks(boolean followSymlinks) {
        this.followSymlinks = followSymlinks;
    }

    @Override
    public boolean isFollowSymlinks() {
        return followSymlinks;
    }
}
