package org.gradle.initialization;

import org.gradle.internal.scripts.DefaultScriptFileResolver;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public abstract class DirectoryInitScriptFinder implements InitScriptFinder {

    protected void findScriptsInDir(File initScriptsDir, Collection<File> scripts) {
        if (!initScriptsDir.isDirectory()) {
            return;
        }
        List<File> found = initScriptsIn(initScriptsDir);
        Collections.sort(found);
        scripts.addAll(found);
    }

    @Nullable
    protected File resolveScriptFile(File dir, String basename) {
        return resolver().resolveScriptFile(dir, basename);
    }

    private List<File> initScriptsIn(File initScriptsDir) {
        return resolver().findScriptsIn(initScriptsDir);
    }

    private DefaultScriptFileResolver resolver() {
        return new DefaultScriptFileResolver();
    }
}
