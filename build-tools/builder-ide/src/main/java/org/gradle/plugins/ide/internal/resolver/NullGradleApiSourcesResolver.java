package org.gradle.plugins.ide.internal.resolver;

import java.io.File;

public class NullGradleApiSourcesResolver implements GradleApiSourcesResolver {

    public static final GradleApiSourcesResolver INSTANCE = new NullGradleApiSourcesResolver();

    private NullGradleApiSourcesResolver() {
    }

    @Override
    public File resolveLocalGroovySources(String jarName) {
        return null;
    }
}
