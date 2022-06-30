package com.tyron.builder.android;

import com.tyron.builder.launcher.ProjectLaunchCase;

import org.junit.Test;

public abstract class BaseProjectTestCase extends ProjectLaunchCase {

    @Test
    public void runTest() {
        this.execute();
    }
}
