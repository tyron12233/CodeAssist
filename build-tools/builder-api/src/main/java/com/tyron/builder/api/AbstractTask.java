package com.tyron.builder.api;

import com.tyron.builder.api.internal.TaskInternal;

public abstract class AbstractTask implements TaskInternal {

    @Override
    public boolean getImpliesSubProjects() {
        return false;
    }
}
