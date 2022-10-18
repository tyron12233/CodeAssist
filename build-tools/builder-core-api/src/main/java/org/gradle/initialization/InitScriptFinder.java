package org.gradle.initialization;

import java.io.File;
import java.util.Collection;

/**
 * Interface for objects that can find init scripts for a given build.
 */
public interface InitScriptFinder {
    void findScripts(Collection<File> scripts);
}
