package org.gradle.initialization;

import java.io.File;
import java.util.Collection;

/**
 * An {@link InitScriptFinder} that includes every *.gradle file in $gradleHome/init.d.
 */
public class DistributionInitScriptFinder extends DirectoryInitScriptFinder {
    final File gradleHome;

    public DistributionInitScriptFinder(File gradleHome) {
        this.gradleHome = gradleHome;
    }

    @Override
    public void findScripts(Collection<File> scripts) {
        if (gradleHome == null) {
            return;
        }
        findScriptsInDir(new File(gradleHome, "init.d"), scripts);
    }

}
