package com.tyron.builder.api;

import com.tyron.builder.launcher.ProjectLaunchCase;

import org.junit.Test;

public abstract class BaseProjectTestCase extends ProjectLaunchCase {

    @Test
    public void runTest() {
        this.execute();
    }
}
