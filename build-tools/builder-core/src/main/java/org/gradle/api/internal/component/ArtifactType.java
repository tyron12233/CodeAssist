package org.gradle.api.internal.component;

import org.gradle.util.internal.GUtil;

public enum ArtifactType {
    SOURCES, JAVADOC, IVY_DESCRIPTOR, MAVEN_POM;

    public String toString() {
        return "'" + GUtil.toWords(name()) + "' artifacts";
    }
}
