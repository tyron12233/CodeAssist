package org.gradle.initialization;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class CompositeInitScriptFinder implements InitScriptFinder {
    private final List<InitScriptFinder> finders;

    public CompositeInitScriptFinder(InitScriptFinder...finders) {
        this.finders = Arrays.asList(finders);
    }

    @Override
    public void findScripts(Collection<File> scripts) {
        for (InitScriptFinder finder : finders) {
            finder.findScripts(scripts);
        }
    }
}
