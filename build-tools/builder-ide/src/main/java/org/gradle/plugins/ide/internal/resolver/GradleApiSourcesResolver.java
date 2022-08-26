package org.gradle.plugins.ide.internal.resolver;

import java.io.File;

public interface GradleApiSourcesResolver {
    File resolveLocalGroovySources(String jarName);
}