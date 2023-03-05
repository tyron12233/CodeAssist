package org.gradle.process.internal;

import org.gradle.process.JavaForkOptions;

public interface JavaForkOptionsInternal extends JavaForkOptions {

    /**
     * Returns true if the given options are compatible with this set of options.
     */
    boolean isCompatibleWith(JavaForkOptions options);

}
