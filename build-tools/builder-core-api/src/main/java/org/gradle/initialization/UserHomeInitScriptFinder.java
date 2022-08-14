package org.gradle.initialization;

import java.io.File;
import java.util.Collection;

public class UserHomeInitScriptFinder extends DirectoryInitScriptFinder implements InitScriptFinder {

    private final File userHomeDir;

    public UserHomeInitScriptFinder(File userHomeDir) {
        this.userHomeDir = userHomeDir;
    }

    @Override
    public void findScripts(Collection<File> scripts) {
        File userInitScript = resolveScriptFile(userHomeDir, "init");
        if (userInitScript != null) {
            scripts.add(userInitScript);
        }
        findScriptsInDir(new File(userHomeDir, "init.d"), scripts);
    }
}

