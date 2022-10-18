package org.gradle.api;

import org.gradle.launcher.ProjectLaunchCase;

import org.junit.Test;

public abstract class BaseProjectTestCase extends ProjectLaunchCase {

    @Test
    public void runTest() {
        this.execute();
    }
}
