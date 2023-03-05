package org.gradle.configuration;

import org.gradle.api.internal.GradleInternal;
import org.gradle.groovy.scripts.BasicScript;
import org.gradle.initialization.InitScript;

public class InitScriptTarget extends DefaultScriptTarget {
    public InitScriptTarget(GradleInternal target) {
        super(target);
    }

    @Override
    public String getId() {
        return "init";
    }

    @Override
    public Class<? extends BasicScript> getScriptClass() {
        return InitScript.class;
    }

    @Override
    public String getClasspathBlockName() {
        return "initscript";
    }

}
