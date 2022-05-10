package com.tyron.builder.configuration;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.groovy.scripts.BasicScript;
import com.tyron.builder.initialization.InitScript;

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
