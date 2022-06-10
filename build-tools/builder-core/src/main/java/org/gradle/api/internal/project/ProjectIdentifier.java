package org.gradle.api.internal.project;

import javax.annotation.Nullable;
import java.io.File;

// TODO need a better name for this
public interface ProjectIdentifier {
    String getName();

    String getPath();

    @Nullable
    ProjectIdentifier getParentIdentifier();

    File getProjectDir();

    File getBuildFile();
}