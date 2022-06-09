package com.tyron.builder.api.internal.component;

import com.tyron.builder.util.internal.GUtil;

public enum ArtifactType {
    SOURCES, JAVADOC, IVY_DESCRIPTOR, MAVEN_POM;

    public String toString() {
        return "'" + GUtil.toWords(name()) + "' artifacts";
    }
}
