package com.tyron.builder.jvm.toolchain;

import com.tyron.builder.api.file.RegularFile;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.Nested;

/**
 * A java compiler used by compile tasks.
 *
 * @since 6.7
 */
public interface JavaCompiler {

    /**
     * Returns metadata information about this tool
     *
     * @return the tool metadata
     */
    @Nested
    JavaInstallationMetadata getMetadata();

    /**
     * Returns the path to the executable for this tool
     *
     * @return the path to the executable
     */
    @Internal
    RegularFile getExecutablePath();
}
