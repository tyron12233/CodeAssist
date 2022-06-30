package com.tyron.builder.internal.jvm;

import javax.annotation.Nullable;
import java.io.File;

public interface JavaInfo {
    /**
     * @return the executable
     * @throws JavaHomeException when executable cannot be found
     */
    File getJavaExecutable() throws JavaHomeException;

    /**
     * @return the executable
     * @throws JavaHomeException when executable cannot be found
     */
    File getJavacExecutable() throws JavaHomeException;

    /**
     * @return the executable
     * @throws JavaHomeException when executable cannot be found
     */
    File getJavadocExecutable() throws JavaHomeException;

    /**
     * @return the executable
     * @throws JavaHomeException when executable cannot be found
     */
    File getExecutable(String name) throws JavaHomeException;

    /**
     * The location of java.
     *
     * @return the java home location
     */
    File getJavaHome();

    /**
     * Returns the tools jar. May return null, for example when Jvm was created via
     * with custom jre location or if jdk is not installed.
     */
    @Nullable
    File getToolsJar();
}
